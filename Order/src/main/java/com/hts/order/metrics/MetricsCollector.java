package com.hts.order.metrics;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Singleton
public final class MetricsCollector {
    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final MeterRegistry registry;

    // Counters
    private final Counter.Builder orderRequestsBuilder;
    private final Counter.Builder errorsBuilder;

    // Timers (HDR Histogram)
    private final Timer.Builder orderLatencyBuilder;
    private final Timer.Builder dbTxDurationBuilder;
    private final Timer.Builder dbBatchDurationBuilder;

    // Gauges
    private final AtomicDouble inQueueDepth;
    private final AtomicDouble outQueueDepth;

    @Inject
    public MetricsCollector(MeterRegistry registry) {
        this.registry = registry;

        // HDR Histogram config: p50, p90, p99, p99.9
        DistributionStatisticConfig histogramConfig = DistributionStatisticConfig.builder()
                .percentiles(0.5, 0.9, 0.99, 0.999)
                .percentilesHistogram(true)
                .serviceLevelObjectives(
                        Duration.ofMillis(1).toNanos(),
                        Duration.ofMillis(5).toNanos(),
                        Duration.ofMillis(10).toNanos(),
                        Duration.ofMillis(50).toNanos(),
                        Duration.ofMillis(100).toNanos()
                )
                .build();

        // Counter builders
        this.orderRequestsBuilder = Counter.builder("order_requests_total")
                .description("Total number of order requests");

        this.errorsBuilder = Counter.builder("errors_total")
                .description("Total number of errors");

        // Timer builders (with HDR)
        this.orderLatencyBuilder = Timer.builder("order_latency_ms")
                .description("Order processing latency")
                .publishPercentileHistogram();

        this.dbTxDurationBuilder = Timer.builder("db_tx_duration_ms")
                .description("Database transaction duration")
                .publishPercentileHistogram();

        this.dbBatchDurationBuilder = Timer.builder("db_batch_duration_ms")
                .description("Database batch insert duration")
                .publishPercentileHistogram();

        // Gauges (queue depth)
        this.inQueueDepth = new AtomicDouble();
        this.outQueueDepth = new AtomicDouble();
        registry.gauge("in_queue_depth", inQueueDepth, AtomicDouble::doubleValue);
        registry.gauge("out_queue_depth", outQueueDepth, AtomicDouble::doubleValue);
    }

    public void recordOrderRequest(short methodId, String resultCode) {
        orderRequestsBuilder
                .tag("methodId", String.valueOf(methodId))
                .tag("resultCode", resultCode)
                .register(registry)
                .increment();
    }

    public void recordError(String exceptionType) {
        errorsBuilder
                .tag("exceptionType", exceptionType)
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordOrderLatency(Timer.Sample sample, short methodId) {
        sample.stop(orderLatencyBuilder
                .tag("methodId", String.valueOf(methodId))
                .register(registry));
    }

    public void recordDbTxDuration(Timer.Sample sample, short serviceId) {
        sample.stop(dbTxDurationBuilder
                .tag("serviceId", String.valueOf(serviceId))
                .register(registry));
    }

    public void recordDbBatchDuration(Timer.Sample sample, int batchSize) {
        sample.stop(dbBatchDurationBuilder
                .tag("batchSize", String.valueOf(batchSize))
                .register(registry));
    }

    public void updateInQueueDepth(double depth) {
        inQueueDepth.set(depth);
    }

    public void updateOutQueueDepth(double depth) {
        outQueueDepth.set(depth);
    }

    // ==================== ORDER SHARD METRICS ====================

    /**
     * 샤드별 큐 사이즈 (Gauge)
     */
    public void recordShardQueueSize(int shardId, long size) {
        Gauge.builder("order.shard.queue.size", () -> size)
             .tag("shard", String.valueOf(shardId))
             .register(registry);
    }

    /**
     * Sub-worker별 큐 사이즈 (Gauge) - Hot queue 탐지용
     */
    public void recordSubQueueSize(int shardId, int subWorker, long size) {
        Gauge.builder("order.shard.subqueue.size", () -> size)
             .tag("shard", String.valueOf(shardId))
             .tag("worker", String.valueOf(subWorker))
             .register(registry);
    }

    /**
     * Sub-worker별 처리량 (Counter)
     */
    public void recordSubWorkerProcessed(int shardId, int subWorker) {
        Counter.builder("order.shard.subworker.processed")
               .tag("shard", String.valueOf(shardId))
               .tag("worker", String.valueOf(subWorker))
               .register(registry)
               .increment();
    }

    /**
     * Sub-worker별 에러 (Counter)
     */
    public void recordWorkerError(int shardId, String errorType) {
        Counter.builder("order.shard.worker.errors")
               .tag("shard", String.valueOf(shardId))
               .tag("error", errorType)
               .register(registry)
               .increment();
    }

    /**
     * Queue full 카운터 (백프레셔 모니터링)
     */
    public void recordQueueFull(int shardId, int subWorker) {
        Counter.builder("order.shard.queue.full")
               .tag("shard", String.valueOf(shardId))
               .tag("worker", String.valueOf(subWorker))
               .register(registry)
               .increment();
    }

    /**
     * Clock backwards 카운터 (VM 시계 역행 감지)
     */
    public void recordClockBackwards(long offsetMs) {
        Counter.builder("order.id.clock.backwards")
               .tag("offset_ms", String.valueOf(offsetMs))
               .register(registry)
               .increment();
    }

    /**
     * 샤드별 latency (Timer)
     */
    public void recordShardLatency(Timer.Sample sample, int shardId) {
        Timer timer = Timer.builder("order.shard.latency")
                           .tag("shard", String.valueOf(shardId))
                           .publishPercentiles(0.95, 0.99)
                           .register(registry);
        sample.stop(timer);
    }

    public void recordWorkerPerf(int shardId, long queueMs, long execMs) {
        long totalMs = queueMs + execMs;

        // Micrometer 타이머 기록
        Timer.builder("order.shard.worker.queue.delay")
                .description("Queue waiting time per shard worker")
                .tag("shard", String.valueOf(shardId))
                .publishPercentileHistogram()
                .register(registry)
                .record(queueMs, TimeUnit.MILLISECONDS);

        Timer.builder("order.shard.worker.exec.duration")
                .description("Execution time per shard worker")
                .tag("shard", String.valueOf(shardId))
                .publishPercentileHistogram()
                .register(registry)
                .record(execMs, TimeUnit.MILLISECONDS);

        Timer.builder("order.shard.worker.total.duration")
                .description("Total processing time (queue + exec)")
                .tag("shard", String.valueOf(shardId))
                .publishPercentileHistogram()
                .register(registry)
                .record(totalMs, TimeUnit.MILLISECONDS);

        // 로그는 샘플링 방식으로만 (100회마다 1번)
        if (shardId % 16 == 0) { // 모든 샤드가 찍으면 로그 폭발하니까 일부만
            log.debug("[PERF] shard={} queue={}ms exec={}ms total={}ms",
                    shardId, queueMs, execMs, totalMs);
        }
    }


    // Nested class for AtomicDouble gauge
    private static class AtomicDouble extends Number {
        private volatile double value;

        void set(double value) {
            this.value = value;
        }

        @Override
        public int intValue() {
            return (int) value;
        }

        @Override
        public long longValue() {
            return (long) value;
        }

        @Override
        public float floatValue() {
            return (float) value;
        }

        @Override
        public double doubleValue() {
            return value;
        }
    }
}
