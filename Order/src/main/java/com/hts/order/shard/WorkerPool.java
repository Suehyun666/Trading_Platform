package com.hts.order.shard;

import com.hts.order.global.OrderIdGenerator;
import com.hts.order.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 큐별 워커 풀 관리
 * - 초기 워커 생성
 * - 동적 워커 추가 (Hot queue 감지 시)
 * - Graceful shutdown
 */
final class WorkerPool {
    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    // 전역 워커 ID 카운터 (충돌 방지)
    private static final AtomicInteger GLOBAL_WORKER_ID_COUNTER = new AtomicInteger(0);

    private final int shardId;
    private final int queueIndex;
    private final BlockingQueue<OrderShardExecutor.OrderTask> queue;
    private final OrderShardExecutor.OrderTaskHandler handler;
    private final MetricsCollector metrics;
    private final WorkerErrorHandler errorHandler;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicInteger workerCount = new AtomicInteger(0);

    WorkerPool(int shardId, int queueIndex,
               BlockingQueue<OrderShardExecutor.OrderTask> queue,
               OrderShardExecutor.OrderTaskHandler handler,
               MetricsCollector metrics,
               WorkerErrorHandler errorHandler) {
        this.shardId = shardId;
        this.queueIndex = queueIndex;
        this.queue = queue;
        this.handler = handler;
        this.metrics = metrics;
        this.errorHandler = errorHandler;
    }

    void startWorkers(int initialCount) {
        for (int i = 0; i < initialCount; i++) {
            addWorker();
        }
    }

    boolean addWorker() {
        int newWorkerNum = workerCount.incrementAndGet();

        // 전역 카운터로 워커 ID 할당 (충돌 완전 제거)
        // 0-63 범위 순환 (6 bits workerId in OrderIdGenerator)
        int uniqueWorkerId = GLOBAL_WORKER_ID_COUNTER.getAndIncrement() & 0x3F;

        OrderIdGenerator idGenerator = new OrderIdGenerator(shardId, uniqueWorkerId, metrics);

        Thread worker = new Thread(() -> runLoop(idGenerator),
            String.format("order-shard-%d-q%d-w%d", shardId, queueIndex, newWorkerNum));
        worker.setDaemon(false);
        worker.start();
        workers.add(worker);

        // Worker 시작 로그 제거 (DEBUG 레벨에서만)

        return true;
    }

    private void runLoop(OrderIdGenerator idGenerator) {
        final String workerName = Thread.currentThread().getName();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                long queueStart = System.nanoTime();
                OrderShardExecutor.OrderTask task = queue.take();
                long queueDelay = (System.nanoTime() - queueStart) / 1_000_000;

                // High queue delay 로그 임계값 상향 (50ms → 200ms)
                if (queueDelay >= 200) {
                    log.warn("[{}] High queue delay: {}ms (shard={}, queue={})",
                        workerName, queueDelay, shardId, queueIndex);
                }

                long execStart = System.nanoTime();
                try {
                    long orderId = idGenerator.nextId();
                    handler.handle(task, orderId);
                } catch (Throwable handlerEx) {
                    if (!errorHandler.handleTaskExecutionError(handlerEx, task, workerName)) {
                        break;
                    }
                } finally {
                    long execMs = (System.nanoTime() - execStart) / 1_000_000;
                    metrics.recordWorkerPerf(task.shardId(), queueDelay, execMs);
                }

            } catch (InterruptedException ie) {
                if (!errorHandler.handleInterruption(workerName)) {
                    break;
                }
            } catch (Throwable fatal) {
                if (!errorHandler.handleFatalError(fatal, workerName)) {
                    break;
                }
            }
        }

        log.warn("[{}] Worker terminated", workerName);
    }

    int getWorkerCount() {
        return workerCount.get();
    }

    int getQueueDepth() {
        return queue.size();
    }

    void shutdown() {
        for (Thread worker : workers) {
            worker.interrupt();
        }

        for (Thread worker : workers) {
            try {
                worker.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
