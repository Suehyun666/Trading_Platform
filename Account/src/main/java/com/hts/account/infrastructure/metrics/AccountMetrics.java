package com.hts.account.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 계좌 서비스 커스텀 메트릭
 *
 * Prometheus에서 확인:
 * - account_reserve_total
 * - account_reserve_failed_total
 * - account_reserve_duration_seconds
 * - account_fill_total
 * - account_db_query_duration_seconds
 */
@ApplicationScoped
public class AccountMetrics {

    private final Counter reserveTotal;
    private final Counter reserveFailed;
    private final Timer reserveDuration;

    private final Counter unreserveTotal;
    private final Counter unreserveFailed;

    private final Counter fillTotal;
    private final Counter fillFailed;
    private final Timer fillDuration;

    private final Timer dbQueryDuration;

    @Inject
    public AccountMetrics(MeterRegistry registry) {
        // Reserve 메트릭
        this.reserveTotal = Counter.builder("account_reserve_total")
            .description("Total number of reserve requests")
            .register(registry);

        this.reserveFailed = Counter.builder("account_reserve_failed_total")
            .description("Total number of failed reserve requests")
            .register(registry);

        this.reserveDuration = Timer.builder("account_reserve_duration_seconds")
            .description("Reserve request duration")
            .register(registry);

        // Unreserve 메트릭
        this.unreserveTotal = Counter.builder("account_unreserve_total")
            .description("Total number of unreserve requests")
            .register(registry);

        this.unreserveFailed = Counter.builder("account_unreserve_failed_total")
            .description("Total number of failed unreserve requests")
            .register(registry);

        // Fill 메트릭
        this.fillTotal = Counter.builder("account_fill_total")
            .description("Total number of fill events processed")
            .register(registry);

        this.fillFailed = Counter.builder("account_fill_failed_total")
            .description("Total number of failed fill events")
            .register(registry);

        this.fillDuration = Timer.builder("account_fill_duration_seconds")
            .description("Fill event processing duration")
            .register(registry);

        // DB 쿼리 메트릭
        this.dbQueryDuration = Timer.builder("account_db_query_duration_seconds")
            .description("Database query duration")
            .register(registry);
    }

    public void recordReserveSuccess(long durationMs) {
        reserveTotal.increment();
        reserveDuration.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordReserveFailed() {
        reserveTotal.increment();
        reserveFailed.increment();
    }

    public void recordUnreserveSuccess() {
        unreserveTotal.increment();
    }

    public void recordUnreserveFailed() {
        unreserveTotal.increment();
        unreserveFailed.increment();
    }

    public void recordFillSuccess(long durationMs) {
        fillTotal.increment();
        fillDuration.record(java.time.Duration.ofMillis(durationMs));
    }

    public void recordFillFailed() {
        fillTotal.increment();
        fillFailed.increment();
    }

    public void recordDbQuery(long durationMs) {
        dbQueryDuration.record(java.time.Duration.ofMillis(durationMs));
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public long stopTimer(Timer.Sample sample) {
        return sample.stop(reserveDuration);
    }
}
