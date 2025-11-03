//package com.hts.account.infrastructure.metrics;
//
//import jakarta.enterprise.context.ApplicationScoped;
//
//import java.util.concurrent.atomic.AtomicLongArray;
//
//import static com.hts.account.api.grpc.AccountShardInvoker.NUM_SHARDS;
//
//@ApplicationScoped
//public class ShardMetrics {
//    private final AtomicLongArray queueSizes = new AtomicLongArray(NUM_SHARDS);
//    private final AtomicLongArray processedCounts = new AtomicLongArray(NUM_SHARDS);
//
//    public void recordEnqueue(int shardIdx) {
//        queueSizes.incrementAndGet(shardIdx);
//    }
//
//    public void recordDequeue(int shardIdx) {
//        queueSizes.decrementAndGet(shardIdx);
//        processedCounts.incrementAndGet(shardIdx);
//    }
//
//    // Prometheus metrics
//    @Gauge(name = "account_shard_queue_size")
//    public long[] getQueueSizes() { ... }
//}
//
