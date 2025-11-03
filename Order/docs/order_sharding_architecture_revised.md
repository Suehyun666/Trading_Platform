# Order 서버 샤딩 아키텍처 (수정본)

> 작성일: 2025-11-02 (revised)
> 목적: 기존 문서의 개념-코드 불일치를 제거하고, "symbol→샤드→샤드 내 병렬→그 샤드에서 orderId 생성"이라는 단일 원칙으로 재정렬한다.

---

## 1. 핵심 원칙

1. **샤드 결정은 I/O 레이어(Dispatch)에서 한 번만 한다.** 서비스 레이어에서는 **샤드 재계산 금지**.
2. **orderId는 샤드에서 생성한다.** 즉, orderId 포맷 안에 샤드 비트가 반드시 포함돼야 한다.
3. **논리 샤드는 16개로 고정**한다. (orderId에 4bit 할당) 이후 확장은 "샤드 내 sub-worker 증설"로 처리한다.
4. **같은 symbol/orderId는 같은 sub-queue**로, **다른 symbol은 같은 샤드여도 다른 sub-queue**로 보내서 병렬 처리한다.
5. **fallback 루트**는 반드시 둔다: `orderId → shard` 실패 시 → `Redis orderIndex` → `DB lookup` → 최후 0번 샤드.

---

## 2. 수정된 비트 포맷 (OrderIdGenerator)

```java
public final class ShardedOrderIdGenerator {
    private static final long EPOCH = 1704067200000L; // 2024-01-01

    private static final long TS_BITS    = 41L;
    private static final long SHARD_BITS = 4L;   // 0..15
    private static final long WORK_BITS  = 6L;   // 0..63
    private static final long SEQ_BITS   = 12L;  // 0..4095

    private static final long SHARD_MASK = (1L << SHARD_BITS) - 1;  // 0xF
    private static final long WORK_MASK  = (1L << WORK_BITS)  - 1;  // 0x3F
    private static final long SEQ_MASK   = (1L << SEQ_BITS)   - 1;  // 0xFFF

    private static final long WORK_SHIFT  = SEQ_BITS;
    private static final long SHARD_SHIFT = SEQ_BITS + WORK_BITS;
    private static final long TS_SHIFT    = SEQ_BITS + WORK_BITS + SHARD_BITS;

    private final long workerId;
    private volatile long lastTs = -1L;
    private volatile long seq = 0L;

    public ShardedOrderIdGenerator(long workerId) {
        this.workerId = workerId & WORK_MASK;
    }

    public synchronized long generate(int shardId) {
        shardId = (int)(shardId & SHARD_MASK);
        long now = System.currentTimeMillis();
        if (now < lastTs) {
            long drift = lastTs - now;
            if (drift > 10) {
                now = lastTs; // hard clamp
            } else {
                now = lastTs; // small backward, also clamp
            }
        }
        if (now == lastTs) {
            seq = (seq + 1) & SEQ_MASK;
            if (seq == 0) {
                // wait next ms
                do {
                    now = System.currentTimeMillis();
                } while (now <= lastTs);
            }
        } else {
            seq = 0L;
        }
        lastTs = now;

        long tsPart    = (now - EPOCH) << TS_SHIFT;
        long shardPart = ((long) shardId) << SHARD_SHIFT;
        long workPart  = (workerId << WORK_SHIFT);
        long seqPart   = seq;

        return tsPart | shardPart | workPart | seqPart;
    }

    public static int extractShard(long orderId) {
        return (int)((orderId >> SHARD_SHIFT) & SHARD_MASK);
    }

    public static int logicalShardCount() {
        return 16; // 반드시 16개로 고정
    }
}
```

- **중요**: 기존 `generate()` (파라미터 없는 것)는 @Deprecated로 남겨두고 내부적으로 `generate(0)`을 부르게 한다. 배포 후 단계적으로 제거.

---

## 3. ConsistentShardSelector (통일 버전)

```java
public final class ConsistentShardSelector {
    private static final int LOGICAL_SHARDS = 16;
    private static final int VIRTUALS_PER_SHARD = 8;
    private final java.util.TreeMap<Integer, Integer> ring = new java.util.TreeMap<>();

    public ConsistentShardSelector() {
        for (int shard = 0; shard < LOGICAL_SHARDS; shard++) {
            for (int v = 0; v < VIRTUALS_PER_SHARD; v++) {
                int h = fnv1a32("shard-" + shard + "-v" + v);
                ring.put(h, shard);
            }
        }
    }

    public int selectBySymbol(String symbol) {
        int h = fnv1a32(symbol);
        var e = ring.ceilingEntry(h);
        if (e == null) {
            e = ring.firstEntry();
        }
        return e.getValue(); // 0..15
    }

    private static int fnv1a32(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int hash = 0x811c9dc5;
        for (byte value : b) {
            hash ^= (value & 0xff);
            hash *= 0x01000193;
        }
        return hash & 0x7fffffff;
    }
}
```

- 이전 문서의 `h = h * 31 + b` 방식은 제거.
- 분포가 나아지고, 가상노드 8개 기준으로 상위 인기심볼 분산이 더 잘 됨.

---

## 4. OrderShardExecutor (bounded queue + routing 고정)

```java
public final class OrderShardExecutor implements AutoCloseable {
    private static final int LOGICAL_SHARDS = 16; // orderId 포맷과 1:1
    private final int subWorkersPerShard;         // ex) 4
    private final ShardGroup[] shards;
    private final OrderTaskHandler handler;

    public OrderShardExecutor(int subWorkersPerShard, OrderTaskHandler handler) {
        this.subWorkersPerShard = subWorkersPerShard;
        this.handler = handler;
        this.shards = new ShardGroup[LOGICAL_SHARDS];
        for (int i = 0; i < LOGICAL_SHARDS; i++) {
            this.shards[i] = new ShardGroup(i, subWorkersPerShard, handler);
        }
    }

    public void submit(OrderTask task) {
        int shardId = task.shardId();
        if (shardId < 0 || shardId >= LOGICAL_SHARDS) {
            shardId = 0; // fallback
        }
        shards[shardId].submit(task);
    }

    @Override
    public void close() {
        for (ShardGroup g : shards) {
            g.shutdown();
        }
    }

    // =====================================================
    public record OrderTask(
            io.netty.channel.Channel channel,
            com.hts.order.core.protocol.PacketHeader header,
            com.hts.order.service.Dto dto,
            int shardId,
            int subKey // symbol.hashCode() or (int)orderId
    ) {
    }

    public interface OrderTaskHandler {
        void handle(OrderTask task) throws Exception;
    }

    // =====================================================
    private static final class ShardGroup {
        private final int shardId;
        private final java.util.List<java.util.concurrent.BlockingQueue<OrderTask>> queues;
        private final java.util.List<Thread> workers;
        private final int routingMod; // 순서 보장용 고정 값

        ShardGroup(int shardId, int subWorkers, OrderTaskHandler handler) {
            this.shardId = shardId;
            this.routingMod = subWorkers; // ★ 이 값을 늘리면 순서 깨지므로 고정
            this.queues = new java.util.ArrayList<>(subWorkers);
            this.workers = new java.util.ArrayList<>(subWorkers);

            for (int i = 0; i < subWorkers; i++) {
                // bounded queue로 OOM 방지
                java.util.concurrent.BlockingQueue<OrderTask> q = new java.util.concurrent.ArrayBlockingQueue<>(65536);
                queues.add(q);

                final int workerNo = i;
                Thread t = new Thread(() -> runLoop(q, handler), "order-shard-" + shardId + "-w" + workerNo);
                t.setDaemon(true);
                t.start();
                workers.add(t);
            }
        }

        void submit(OrderTask task) {
            int idx = Math.abs(task.subKey()) % routingMod; // ★ routingMod 고정
            queues.get(idx).offer(task); // offer 실패 시 dropped 처리도 가능
        }

        private void runLoop(java.util.concurrent.BlockingQueue<OrderTask> q, OrderTaskHandler handler) {
            for (; ; ) {
                try {
                    OrderTask task = q.take();
                    handler.handle(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    // 절대 워커 죽지 않게: 로그만 찍고 다시 돈다
                    t.printStackTrace();
                }
            }
        }

        void shutdown() {
            for (Thread t : workers) {
                t.interrupt();
            }
        }
    }
}
```

- **수정 포인트 반영**:
  - `LinkedBlockingQueue` → `ArrayBlockingQueue`로 통일
  - sub-worker 증설 시 라우팅이 바뀌지 않도록 `routingMod`를 고정
  - 워커 예외로 죽지 않게 무한루프

---

## 5. DispatchHandler (ORDER 전용 경로 분리)

```java
public final class DispatchHandler extends SimpleChannelInboundHandler<MessageEnvelope> {

    private final DtoMapper dtoMapper;
    private final HandlerRegistry handlerRegistry;
    private final ConsistentShardSelector shardSelector;
    private final OrderShardExecutor orderShardExecutor;
    private final java.util.concurrent.ExecutorService blockingPool; // 다른 서비스용

    public DispatchHandler(...) {
        // ... 생성자에서 주입
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageEnvelope envelope) throws Exception {
        var header = envelope.header();
        var message = envelope.payload();
        var dto = dtoMapper.toDto(header.getServiceId(), header.getMethodId(), message);

        if (header.getServiceId() == PacketHeader.SERVICE_ORDER) {
            int shardId;
            int subKey;
            if (header.getMethodId() == 1) { // NewOrder
                NewOrderRequest req = (NewOrderRequest) message;
                String symbol = req.getSymbol();
                shardId = shardSelector.selectBySymbol(symbol);
                subKey  = symbol.hashCode();
            } else if (header.getMethodId() == 2) { // Cancel
                CancelOrderRequest req = (CancelOrderRequest) message;
                long orderId = req.getOrderId();
                shardId = ShardedOrderIdGenerator.extractShard(orderId);
                if (shardId < 0 || shardId >= ShardedOrderIdGenerator.logicalShardCount()) {
                    // fallback: 구 포맷이거나 외부 주문ID
                    // 1) Redis → 2) DB → 3) 0번
                    String symbol = orderIndexCache.getSymbol(orderId);
                    if (symbol != null) {
                        shardId = shardSelector.selectBySymbol(symbol);
                        subKey = symbol.hashCode();
                    } else {
                        shardId = 0;
                        subKey = (int) orderId;
                    }
                } else {
                    subKey = (int) orderId;
                }
            } else {
                shardId = 0;
                subKey = 0;
            }

            OrderShardExecutor.OrderTask task = new OrderShardExecutor.OrderTask(
                    ctx.channel(), header, dto, shardId, subKey
            );
            orderShardExecutor.submit(task);
            return;
        }

        // 나머지 서비스는 기존 공용풀 사용
        var handler = handlerRegistry.getHandler(header.getServiceId());
        blockingPool.submit(() -> handler

