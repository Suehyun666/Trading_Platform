package com.hts.order.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Singleton
public final class MetricsReporter {
    private static final Logger log = LoggerFactory.getLogger(MetricsReporter.class);
    private final MeterRegistry registry;
    private final ScheduledExecutorService scheduler;

    @Inject
    public MetricsReporter(MeterRegistry registry) {
        this.registry = registry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName("metrics-reporter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::report, 10, 10, TimeUnit.SECONDS);
        log.info("MetricsReporter started (reporting every 10 seconds)");
    }

    private void report() {
        // StringBuilder로 모든 로그를 배치 처리 (AsyncAppender 큐 경합 감소)
        StringBuilder sb = new StringBuilder(2048);
        sb.append("========== METRICS REPORT ==========\n");

        // Order request counters
        registry.find("order_requests_total").counters().forEach(counter -> {
            if (counter.count() > 0) {
                sb.append(String.format("order_requests_total[%s] = %d\n",
                        counter.getId().getTags(), (long) counter.count()));
            }
        });

        // Order latency (mean, max)
        registry.find("order_latency_ms").timers().forEach(timer -> {
            if (timer.count() > 0) {
                sb.append(String.format("order_latency_ms[%s] count=%d mean=%.2fms max=%.2fms total=%.2fs\n",
                        timer.getId().getTags(),
                        timer.count(),
                        timer.mean(TimeUnit.MILLISECONDS),
                        timer.max(TimeUnit.MILLISECONDS),
                        timer.totalTime(TimeUnit.SECONDS)));
            }
        });

        // DB transaction duration
        registry.find("db_tx_duration_ms").timers().forEach(timer -> {
            if (timer.count() > 0) {
                sb.append(String.format("db_tx_duration_ms[%s] count=%d mean=%.2fms max=%.2fms\n",
                        timer.getId().getTags(),
                        timer.count(),
                        timer.mean(TimeUnit.MILLISECONDS),
                        timer.max(TimeUnit.MILLISECONDS)));
            }
        });

        // Errors
        registry.find("errors_total").counters().forEach(counter -> {
            if (counter.count() > 0) {
                sb.append(String.format("errors_total[%s] = %d\n",
                        counter.getId().getTags(), (long) counter.count()));
            }
        });

        // Queue depth
        registry.find("in_queue_depth").gauges().forEach(gauge -> {
            sb.append(String.format("in_queue_depth = %.0f\n", gauge.value()));
        });
        registry.find("out_queue_depth").gauges().forEach(gauge -> {
            sb.append(String.format("out_queue_depth = %.0f\n", gauge.value()));
        });

        sb.append("====================================");

        // 한 번에 출력 (AsyncAppender 큐에 1번만 enqueue)
        log.info(sb.toString());
    }

    public void stop() {
        scheduler.shutdown();
    }
}
