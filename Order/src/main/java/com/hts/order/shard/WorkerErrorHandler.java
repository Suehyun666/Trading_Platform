package com.hts.order.shard;

import com.hts.order.global.ResponseUtil;
import com.hts.order.metrics.MetricsCollector;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker 에러 처리 전략
 * - Handler 예외: 로그 + 클라이언트 에러 응답
 * - InterruptedException: Graceful shutdown
 * - Fatal 에러: 재시작 (OOM 제외)
 */
final class WorkerErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerErrorHandler.class);
    private static final long RECOVERY_DELAY_MS = 1000;

    private final MetricsCollector metrics;

    WorkerErrorHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    boolean handleTaskExecutionError(Throwable error, OrderShardExecutor.OrderTask task, String workerName) {
        log.error("[{}] Task execution failed: shardId={}, subKey={}",
                  workerName, task.shardId(), task.subKey(), error);

        metrics.recordWorkerError(task.shardId(), error.getClass().getSimpleName());

        try {
            ResponseUtil.sendError(task.channel(), task.header(), 500, "Internal processing error");
        } catch (Exception responseEx) {
            log.error("[{}] Failed to send error response", workerName, responseEx);
        }
        return true; // Continue processing
    }

    boolean handleInterruption(String workerName) {
        log.info("[{}] Worker interrupted, shutting down gracefully", workerName);
        Thread.currentThread().interrupt();
        return false; // Stop processing
    }

    boolean handleFatalError(Throwable fatal, String workerName) {
        log.error("[{}] Fatal error in worker loop, attempting recovery", workerName, fatal);

        if (fatal instanceof OutOfMemoryError) {
            log.error("[{}] OOM detected, terminating worker", workerName);
            return false; // Stop processing
        }

        try {
            Thread.sleep(RECOVERY_DELAY_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true; // Retry
    }
}
