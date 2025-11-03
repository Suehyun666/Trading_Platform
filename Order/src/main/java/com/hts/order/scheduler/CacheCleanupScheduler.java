package com.hts.order.scheduler;

import com.hts.order.cache.OrderIndexCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OrderIndexCache 정기 정리 스케줄러
 *
 * - 매월 1일 02:00에 90일 이상 지난 주문 캐시 삭제
 * - 실제로는 매일 02:00에 실행 (간단한 구현)
 * - 프로덕션에서는 Quartz 등으로 교체 권장
 */
@Singleton
public class CacheCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(CacheCleanupScheduler.class);

    private final OrderIndexCache orderIndexCache;
    private final ScheduledExecutorService scheduler;

    @Inject
    public CacheCleanupScheduler(OrderIndexCache orderIndexCache) {
        this.orderIndexCache = orderIndexCache;
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "cache-cleanup-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 스케줄러 시작
     *
     * 매일 02:00에 실행하도록 초기 지연 계산
     */
    public void start() {
        // 현재 시각으로부터 다음 02:00까지 시간 계산
        long initialDelayHours = calculateInitialDelay();

        // 매일 02:00에 실행 (24시간 주기)
        scheduler.scheduleAtFixedRate(
            this::runCleanup,
            initialDelayHours,
            24, // 24시간마다
            TimeUnit.HOURS
        );

        log.info("CacheCleanupScheduler started: initial delay {} hours, then every 24 hours",
                 initialDelayHours);
    }

    /**
     * 다음 02:00까지 남은 시간 계산
     *
     * @return hours until next 2 AM
     */
    private long calculateInitialDelay() {
        java.time.LocalTime now = java.time.LocalTime.now();
        java.time.LocalTime targetTime = java.time.LocalTime.of(2, 0);

        long hoursUntilTarget;
        if (now.isBefore(targetTime)) {
            // 오늘 02:00이 아직 안 지남
            hoursUntilTarget = java.time.Duration.between(now, targetTime).toHours();
        } else {
            // 오늘 02:00이 지났으므로 내일 02:00까지
            java.time.LocalTime tomorrow2AM = targetTime.plusHours(24);
            hoursUntilTarget = java.time.Duration.between(now, tomorrow2AM).toHours();
        }

        return Math.max(1, hoursUntilTarget); // 최소 1시간
    }

    /**
     * Cleanup 작업 실행
     */
    private void runCleanup() {
        try {
            log.info("Starting scheduled OrderIndexCache cleanup");
            orderIndexCache.cleanupOldOrders();
            log.info("Completed scheduled OrderIndexCache cleanup");
        } catch (Exception e) {
            log.error("Failed to run scheduled cache cleanup", e);
        }
    }

    /**
     * Graceful shutdown
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CacheCleanupScheduler");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
