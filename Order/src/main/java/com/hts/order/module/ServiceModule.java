package com.hts.order.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.hts.order.cache.OrderIndexCache;
import com.hts.order.client.AccountGrpcClient;
import com.hts.order.config.AccountServiceConfig;
import com.hts.order.core.TransactionExecutor;
import com.hts.order.global.DefaultHandlerRegistry;
import com.hts.order.global.DtoMapper;
import com.hts.order.global.HandlerRegistry;
import com.hts.order.global.OrderIdGenerator;
import com.hts.order.global.ProtobufParser;
import com.hts.order.core.protocol.PacketHeader;
import com.hts.order.metrics.MetricsCollector;
import com.hts.order.outbox.OrderBatchWriter;
import com.hts.order.outbox.OrderOutboxQueue;
import com.hts.order.repository.OrderRepository;
import com.hts.order.repository.SessionRepository;
import com.hts.order.scheduler.CacheCleanupScheduler;
import com.hts.order.service.order.OrderService;
import com.hts.order.shard.ShardSelector;
import com.hts.order.shard.ModuloShardSelector;
import com.hts.order.shard.ConsistentShardSelector;
import com.hts.order.shard.OrderShardExecutor;
import io.lettuce.core.RedisClient;
import org.jooq.DSLContext;

import javax.inject.Singleton;

public final class ServiceModule extends AbstractModule {

    @Provides
    @Singleton
    HandlerRegistry provideHandlerRegistry() {
        HandlerRegistry registry = new DefaultHandlerRegistry();
        // OrderService는 더 이상 Handler가 아니므로 등록하지 않음
        // ORDER 요청은 DispatchHandler에서 OrderShardExecutor로 직접 라우팅됨
        return registry;
    }

    @Provides
    @Singleton
    ProtobufParser provideProtobufParser() {
        ProtobufParser parser = new ProtobufParser();
        parser.register(PacketHeader.SERVICE_ORDER, (short) 1, com.hts.order.proto.OrderProto.NewOrderRequest.parser());
        parser.register(PacketHeader.SERVICE_ORDER, (short) 2, com.hts.order.proto.OrderProto.CancelOrderRequest.parser());
        return parser;
    }

    @Provides
    @Singleton
    DtoMapper provideDtoMapper() {
        return new DtoMapper();
    }

    /**
     * ShardSelector 구현체 선택
     *
     * 현재: ModuloShardSelector (완벽한 균등 분산)
     * 대안: ConsistentShardSelector (노드 추가/제거 시 유리)
     *
     * 전환 방법: return new ConsistentShardSelector();
     */
    @Provides
    @Singleton
    ShardSelector provideShardSelector() {
        // 단순 모듈로 방식 (균등 분산 보장)
        return new ModuloShardSelector();

        // Consistent Hashing 방식 (필요 시 주석 해제)
        // return new ConsistentShardSelector();
    }

    @Provides
    @Singleton
    OrderIndexCache provideOrderIndexCache(RedisClient redisClient, OrderRepository orderRepository) {
        return new OrderIndexCache(redisClient, orderRepository);
    }

    @Provides
    @Singleton
    OrderShardExecutor provideOrderShardExecutor(OrderService orderService, MetricsCollector metrics) {
        return new OrderShardExecutor(orderService, metrics);
    }

    @Provides
    @Singleton
    OrderOutboxQueue provideOrderOutboxQueue() {
        return new OrderOutboxQueue();
    }

    @Provides
    @Singleton
    OrderBatchWriter provideOrderBatchWriter(OrderOutboxQueue outboxQueue,
                                            DSLContext dsl,
                                            MetricsCollector metrics) {
        OrderBatchWriter writer = new OrderBatchWriter(outboxQueue, dsl, metrics);
        writer.start();  // 스레드 풀 시작
        return writer;
    }

    @Provides
    @Singleton
    OrderService provideOrderService(
            TransactionExecutor transactionExecutor,
            AccountGrpcClient accountGrpcClient,
            OrderRepository orderRepository,
            SessionRepository sessionRepository,
            OrderIndexCache orderIndexCache,
            OrderOutboxQueue outboxQueue,
            MetricsCollector metrics) {
        return new OrderService(
                transactionExecutor,
                accountGrpcClient,
                orderRepository,
                sessionRepository,
                orderIndexCache,
                outboxQueue,
                metrics
        );
    }

    @Provides
    @Singleton
    AccountGrpcClient provideAccountGrpcClient(AccountServiceConfig config) {
        return new AccountGrpcClient(config.getHost(), config.getPort());
    }

    @Provides
    @Singleton
    OrderRepository provideOrderRepository(DSLContext dsl) {
        return new OrderRepository(dsl);
    }

    // OrderIdGenerator는 더 이상 singleton으로 제공되지 않음
    // 각 워커가 독립적인 인스턴스를 생성 (per-worker isolation)

    @Provides
    @Singleton
    CacheCleanupScheduler provideCacheCleanupScheduler(OrderIndexCache orderIndexCache) {
        return new CacheCleanupScheduler(orderIndexCache);
    }
}
