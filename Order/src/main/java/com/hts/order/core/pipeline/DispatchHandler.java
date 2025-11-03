package com.hts.order.core.pipeline;

import com.google.common.hash.Hashing;
import com.google.protobuf.Message;
import com.hts.order.cache.OrderIndexCache;
import com.hts.order.config.OrderExecutorConfig;
import com.hts.order.core.protocol.MessageEnvelope;
import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.global.DtoMapper;
import com.hts.order.global.HandlerRegistry;
import com.hts.order.global.OrderIdGenerator;
import com.hts.order.proto.OrderProto;
import com.hts.order.repository.OrderRepository;
import com.hts.order.service.Handler;
import com.hts.order.service.exception.ServiceException;
import com.hts.order.service.order.OrderDto;
import com.hts.order.shard.ShardSelector;
import com.hts.order.shard.OrderShardExecutor;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;

public final class DispatchHandler extends SimpleChannelInboundHandler<MessageEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(DispatchHandler.class);

    private final HandlerRegistry handlerRegistry;
    private final DtoMapper dtoMapper;
    private final ExecutorService blockingPool;

    // ORDER 샤딩을 위한 추가 의존성
    private final ShardSelector shardSelector;
    private final OrderShardExecutor orderShardExecutor;
    private final OrderIndexCache orderIndexCache;
    private final OrderRepository orderRepository;
    private final OrderExecutorConfig orderExecutorConfig;

    public DispatchHandler(HandlerRegistry handlerRegistry,
                          DtoMapper dtoMapper,
                          ExecutorService blockingPool,
                          ShardSelector shardSelector,
                          OrderShardExecutor orderShardExecutor,
                          OrderIndexCache orderIndexCache,
                          OrderRepository orderRepository,
                          OrderExecutorConfig orderExecutorConfig) {
        this.handlerRegistry = handlerRegistry;
        this.dtoMapper = dtoMapper;
        this.blockingPool = blockingPool;
        this.shardSelector = shardSelector;
        this.orderShardExecutor = orderShardExecutor;
        this.orderIndexCache = orderIndexCache;
        this.orderRepository = orderRepository;
        this.orderExecutorConfig = orderExecutorConfig;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MessageEnvelope envelope) {
        PacketHeader header = envelope.header();
        Message message = envelope.payload();

        OrderDto dto = dtoMapper.toDto(header.getServiceId(), header.getMethodId(), message);

        // ORDER 서비스 라우팅 결정 (Canary 또는 샤드)
        if (header.getServiceId() == PacketHeader.SERVICE_ORDER) {
            if (shouldUseShardExecutor()) {
                // 샤드 라우팅
                routeOrderRequest(ctx, header, message, dto);
            } else {
                // 기존 blockingPool 방식 (Canary 비활성화 또는 확률 밖)
                routeToBlockingPool(ctx, header, dto);
            }
            return;
        }

        // 다른 서비스는 기존 blockingPool 방식 유지
        Handler handler = handlerRegistry.getHandler(header.getServiceId());
        if (handler == null) {
            throw new ServiceException("Invalid Message", header, 400);
        }

        CompletableFuture
                .runAsync(() -> handler.handle(ctx.channel(), header, dto), blockingPool)
                .exceptionally(ex -> {
                    ctx.executor().execute(() -> ctx.fireExceptionCaught(ex));
                    return null;
                });
    }

    /**
     * ORDER 요청 샤드 라우팅
     *
     * - NewOrder: symbol → MurmurHash3 → shardId (16) + subKey (4 lanes)
     * - Cancel: orderId → extractShard() → shardId (fallback: Redis → DB → 0)
     */
    private void routeOrderRequest(ChannelHandlerContext ctx, PacketHeader header, Message message, OrderDto dto) {
        int shardId;
        int subKey;

        if (header.getMethodId() == 1) {  // NewOrder with symbol
            OrderProto.NewOrderRequest req = (OrderProto.NewOrderRequest) message;
            String symbol = req.getSymbol();

            // MurmurHash3 (Guava) + xor folding (균등 분산 보장)
            int hash = Hashing.murmur3_32_fixed(0x9747b28c)
                    .hashString(symbol, StandardCharsets.UTF_8)
                    .asInt();
            int mixed = hash ^ (hash >>> 16);  // 상하위 비트 상관성 제거

            shardId = mixed & 0x0F;            // 하위 4비트 → 16샤드 (0-15)
            subKey = (mixed >>> 4) & 0x03;     // 다음 2비트 → 4레인 (0-3)

            log.debug("NewOrder: symbol={} hash={} mixed={} → shard={}, lane={}",
                      symbol, Integer.toHexString(hash), Integer.toHexString(mixed), shardId, subKey);

        } else if (header.getMethodId() == 2) {  // Cancel (orderId만 있음)
            OrderProto.CancelOrderRequest req = (OrderProto.CancelOrderRequest) message;
            long orderId = req.getOrderId();

            if (OrderIdGenerator.isExternal(orderId)) {
                // 외부 주문 (음수 orderId)
                shardId = OrderIdGenerator.extractShard(orderId);
                log.debug("External orderId={} → shard={}", orderId, shardId);

            } else {
                // 내부 주문 - orderId 비트에서 추출
                shardId = OrderIdGenerator.extractShard(orderId);

                // Fallback 체인 (구 포맷 or 추출 실패)
                if (shardId < 0) {
                    log.warn("extractShard failed for orderId={}, attempting fallback", orderId);

                    // Step 1: Redis lookup (빠름 - 1ms)
                    String symbol = orderIndexCache.getSymbol(orderId);
                    if (symbol != null) {
                        int hash = Hashing.murmur3_32_fixed(0x9747b28c)
                                .hashString(symbol, StandardCharsets.UTF_8)
                                .asInt();
                        int mixed = hash ^ (hash >>> 16);
                        shardId = mixed & 0x0F;  // MurmurHash3 적용
                        log.info("Fallback-Redis: orderId={} → symbol={} → shard={}",
                                 orderId, symbol, shardId);
                    } else {
                        // Step 2: DB lookup (느림 - 10ms, but 확실)
                        symbol = orderRepository.getSymbolByOrderId(orderId);
                        if (symbol != null) {
                            int hash = Hashing.murmur3_32_fixed(0x9747b28c)
                                    .hashString(symbol, StandardCharsets.UTF_8)
                                    .asInt();
                            int mixed = hash ^ (hash >>> 16);
                            shardId = mixed & 0x0F;  // MurmurHash3 적용
                            log.warn("Fallback-DB: orderId={} → symbol={} → shard={}",
                                     orderId, symbol, shardId);

                            // Redis에 캐싱 (다음 조회 최적화)
                            orderIndexCache.index(orderId, symbol);
                        } else {
                            // Step 3: 최후 방어 (주문 없음)
                            shardId = 0;
                            log.error("Fallback failed: orderId={} not found, routing to shard 0",
                                      orderId);
                        }
                    }
                }
            }

            // subKey는 orderId (같은 주문의 취소 요청은 같은 sub-queue)
            subKey = (int)orderId;

        } else {
            // 기타 메서드 (미래 확장용)
            shardId = 0;
            subKey = 0;
        }

        // OrderTask 생성 및 샤드 제출
        OrderShardExecutor.OrderTask task =
            new OrderShardExecutor.OrderTask(ctx.channel(), header, dto, shardId, subKey);
        orderShardExecutor.submit(task);
    }

    /**
     * Canary rollout 결정
     *
     * - enabled=false: 항상 blockingPool
     * - enabled=true, canary=0: 항상 blockingPool
     * - enabled=true, canary=100: 항상 shardExecutor
     * - enabled=true, canary=5: 5% 확률로 shardExecutor
     *
     * @return true if should use OrderShardExecutor
     */
    private boolean shouldUseShardExecutor() {
        if (!orderExecutorConfig.isEnabled()) {
            return false;
        }

        int canaryPercent = orderExecutorConfig.getCanaryPercent();
        if (canaryPercent <= 0) {
            return false;
        }
        if (canaryPercent >= 100) {
            return true;
        }

        // 랜덤 확률로 결정 (ThreadLocalRandom - 빠름)
        int random = ThreadLocalRandom.current().nextInt(100);
        return random < canaryPercent;
    }

    /**
     * ORDER 요청을 기존 blockingPool로 라우팅 (Canary 비활성화 시)
     */
    private void routeToBlockingPool(ChannelHandlerContext ctx, PacketHeader header, OrderDto dto) {
        Handler handler = handlerRegistry.getHandler(header.getServiceId());
        if (handler == null) {
            throw new ServiceException("Invalid Message", header, 400);
        }

        CompletableFuture
                .runAsync(() -> handler.handle(ctx.channel(), header, dto), blockingPool)
                .exceptionally(ex -> {
                    ctx.executor().execute(() -> ctx.fireExceptionCaught(ex));
                    return null;
                });
    }

}