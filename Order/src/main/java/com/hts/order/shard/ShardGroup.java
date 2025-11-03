package com.hts.order.shard;

import com.hts.order.global.OrderIdGenerator;
import com.hts.order.global.ResponseUtil;
import com.hts.order.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 단일 샤드의 sub-queue + worker pool 관리
 * - 여러 sub-queue (symbol 기반 라우팅)
 * - 각 큐별 독립 WorkerPool
 * - Hot queue 감지 및 동적 스케일링
 */
final class ShardGroup {
    private static final Logger log = LoggerFactory.getLogger(ShardGroup.class);

    private final int shardId;
    private final int queueCount;
    private final List<BlockingQueue<OrderShardExecutor.OrderTask>> queues;
    private final List<WorkerPool> workerPools;
    private final MetricsCollector metrics;

    ShardGroup(int shardId, int queueCount, int initialWorkersPerQueue, int queueCapacity,
               OrderShardExecutor.OrderTaskHandler handler, MetricsCollector metrics) {
        this.shardId = shardId;
        this.queueCount = queueCount;
        this.queues = new ArrayList<>(queueCount);
        this.workerPools = new ArrayList<>(queueCount);
        this.metrics = metrics;

        WorkerErrorHandler errorHandler = new WorkerErrorHandler(metrics);

        for (int queueIdx = 0; queueIdx < queueCount; queueIdx++) {
            BlockingQueue<OrderShardExecutor.OrderTask> queue = new ArrayBlockingQueue<>(queueCapacity);
            queues.add(queue);

            // WorkerPool에 handler/errorHandler만 전달 (각 워커가 독립 ID generator 생성)
            WorkerPool pool = new WorkerPool(shardId, queueIdx, queue, handler, metrics, errorHandler);
            pool.startWorkers(initialWorkersPerQueue);
            workerPools.add(pool);
        }

        log.info("ShardGroup-{} initialized: {} queues, {} workers/queue, capacity={}",
                 shardId, queueCount, initialWorkersPerQueue, queueCapacity);
    }

    void submit(OrderShardExecutor.OrderTask task) {
        int queueIdx = Math.abs(task.subKey()) % queueCount;

        if (!queues.get(queueIdx).offer(task)) {
            log.error("Shard {} queue {} is full, rejecting task", shardId, queueIdx);
            metrics.recordQueueFull(shardId, queueIdx);
            ResponseUtil.sendError(task.channel(), task.header(), 503, "Server overloaded");
        }
    }

    int detectAndScaleHotQueues(HotQueueDetector detector) {
        return detector.detectAndScale(shardId, workerPools);
    }

    boolean isQueueEmpty() {
        return queues.stream().allMatch(Queue::isEmpty);
    }

    void shutdown() {
        for (WorkerPool pool : workerPools) {
            pool.shutdown();
        }
    }
}
