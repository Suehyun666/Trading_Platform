package com.hts.order.shard;

import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.global.ResponseUtil;
import com.hts.order.metrics.MetricsCollector;
import com.hts.order.service.order.OrderDto;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 샤드별 Order 처리 Executor
 *
 * 아키텍처:
 * - 16개 논리 샤드 (고정)
 * - 각 샤드당 4개 sub-queue (symbol 기반 라우팅)
 * - 큐당 초기 1개 워커, Hot queue 감지 시 최대 3개까지 증설
 * - 총 64개 큐, 초기 64개 워커 → 최대 192개 워커
 */
public final class OrderShardExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(OrderShardExecutor.class);

    private static final int SHARD_COUNT = 16;
    private static final int SUB_QUEUES_PER_SHARD = 4;
    private static final int INITIAL_WORKERS_PER_QUEUE = 1;
    private static final int QUEUE_CAPACITY = 8192;
    private static final int HOT_QUEUE_THRESHOLD = 50;   // 100 → 50 (빠른 감지)
    private static final int MAX_WORKERS_PER_QUEUE = 6;  // 3 → 6 (높은 처리량)
    private static final long MONITOR_INTERVAL_MS = 200;

    private final List<ShardGroup> shards = new ArrayList<>(SHARD_COUNT);
    private final HotQueueDetector hotQueueDetector;
    private final ScheduledExecutorService hotQueueMonitor;
    private final OrderTaskHandler handler;
    private volatile boolean shuttingDown = false;

    public OrderShardExecutor(OrderTaskHandler handler, MetricsCollector metrics) {
        this.handler = handler;
        this.hotQueueDetector = new HotQueueDetector(HOT_QUEUE_THRESHOLD, MAX_WORKERS_PER_QUEUE, metrics);

        for (int s = 0; s < SHARD_COUNT; s++) {
            shards.add(new ShardGroup(s, SUB_QUEUES_PER_SHARD, INITIAL_WORKERS_PER_QUEUE,
                                     QUEUE_CAPACITY, handler, metrics));
        }

        this.hotQueueMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hot-queue-monitor");
            t.setDaemon(true);
            return t;
        });

        hotQueueMonitor.scheduleAtFixedRate(this::detectAndScaleHotQueues,
            MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);

        log.info("OrderShardExecutor initialized: {} shards, {} queues/shard, threshold={}, interval={}ms",
                 SHARD_COUNT, SUB_QUEUES_PER_SHARD, HOT_QUEUE_THRESHOLD, MONITOR_INTERVAL_MS);
    }

    public void submit(OrderTask task) {
        if (shuttingDown) {
            ResponseUtil.sendError(task.channel(), task.header(), 503, "Server is shutting down");
            return;
        }

        int shardId = task.shardId();
        if (shardId < 0 || shardId >= SHARD_COUNT) {
            log.warn("Invalid shardId={}, using fallback 0", shardId);
            shardId = 0;
        }

        shards.get(shardId).submit(task);
    }

    private void detectAndScaleHotQueues() {
        try {
            int totalHotQueues = 0;
            for (ShardGroup shard : shards) {
                totalHotQueues += shard.detectAndScaleHotQueues(hotQueueDetector);
            }

            // Hot queue 스캔 로그 제거 (개별 증설 로그만 유지)
        } catch (Exception e) {
            log.error("Hot queue detection failed", e);
        }
    }

    @PreDestroy
    public void initiateShutdown() {
        log.info("OrderShardExecutor shutdown initiated");
        shuttingDown = true;

        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allEmpty = shards.stream().allMatch(ShardGroup::isQueueEmpty);
            if (allEmpty) {
                log.info("All queues drained");
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        hotQueueMonitor.shutdown();
        close();
        log.info("OrderShardExecutor shutdown complete");
    }

    @Override
    public void close() {
        for (ShardGroup shard : shards) {
            shard.shutdown();
        }
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Order 작업 (Record)
     */
    public record OrderTask(
        Channel channel,
        PacketHeader header,
        OrderDto dto,
        int shardId,
        int subKey
    ) {}

    /**
     * Order 작업 핸들러 인터페이스
     */
    public interface OrderTaskHandler {
        void handle(OrderTask task, long orderId);
    }
}
