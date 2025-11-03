package com.hts.order.shard;

import com.hts.order.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Hot SubQueue 자동 감지 및 스케일링
 * - 큐 depth 모니터링
 * - 임계값 초과 시 워커 추가
 */
final class HotQueueDetector {
    private static final Logger log = LoggerFactory.getLogger(HotQueueDetector.class);

    private final int threshold;
    private final int maxWorkersPerQueue;
    private final MetricsCollector metrics;

    HotQueueDetector(int threshold, int maxWorkersPerQueue, MetricsCollector metrics) {
        this.threshold = threshold;
        this.maxWorkersPerQueue = maxWorkersPerQueue;
        this.metrics = metrics;
    }

    int detectAndScale(int shardId, List<WorkerPool> pools) {
        int hotQueueCount = 0;

        for (int queueIdx = 0; queueIdx < pools.size(); queueIdx++) {
            WorkerPool pool = pools.get(queueIdx);
            int queueDepth = pool.getQueueDepth();

            metrics.recordSubQueueSize(shardId, queueIdx, queueDepth);

            if (queueDepth > threshold) {
                int currentWorkers = pool.getWorkerCount();

                if (currentWorkers < maxWorkersPerQueue) {
                    pool.addWorker();
                    hotQueueCount++;

                    // Hot queue 증설 시에만 로그 (간결하게)
                    log.info("Hot queue scaled: shard={} queue={} depth={} workers={}->{}",
                             shardId, queueIdx, queueDepth, currentWorkers, currentWorkers + 1);
                }
            }
        }

        return hotQueueCount;
    }
}
