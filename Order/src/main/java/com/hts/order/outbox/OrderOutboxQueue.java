package com.hts.order.outbox;

import com.hts.order.service.order.OrderEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 비동기 주문 DB 저장 큐
 *
 * - Worker 스레드에서 즉시 offer (1μs 이내)
 * - BatchWriter 스레드가 비동기로 DB에 batch insert
 * - Graceful shutdown 지원
 */
@Singleton
public final class OrderOutboxQueue {
    private static final Logger log = LoggerFactory.getLogger(OrderOutboxQueue.class);

    private static final int QUEUE_CAPACITY = 500_000;  // 100k → 500k (버퍼 5배)

    private final BlockingQueue<OrderEntity> queue;
    private volatile boolean shuttingDown = false;

    public OrderOutboxQueue() {
        this.queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        log.info("OrderOutboxQueue initialized: capacity={}", QUEUE_CAPACITY);
    }

    /**
     * 주문을 큐에 추가 (비차단, 빠른 반환)
     *
     * @param order 주문 엔티티
     * @return true if added, false if queue full
     */
    public boolean offer(OrderEntity order) {
        if (shuttingDown) {
            log.warn("Rejecting order during shutdown: orderId={}", order.orderId());
            return false;
        }

        boolean added = queue.offer(order);
        if (!added) {
            log.error("Outbox queue full! orderId={}, queueSize={}",
                     order.orderId(), queue.size());
        }
        return added;
    }

    /**
     * 큐에서 주문 배치 poll (BatchWriter용)
     *
     * @param batch 배치 버퍼
     * @param maxSize 최대 배치 크기
     * @param timeoutMs 대기 시간 (ms)
     * @return polled count
     */
    public int pollBatch(java.util.List<OrderEntity> batch, int maxSize, long timeoutMs)
            throws InterruptedException {
        batch.clear();

        // 첫 번째 요소 대기
        OrderEntity first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return 0;
        }

        batch.add(first);

        // 나머지 즉시 drain
        int drained = queue.drainTo(batch, maxSize - 1);
        return 1 + drained;
    }

    /**
     * 현재 큐 크기
     */
    public int size() {
        return queue.size();
    }

    /**
     * 큐가 비었는지 확인
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Shutdown 시작 (새 요청 거부)
     */
    @PreDestroy
    public void initiateShutdown() {
        log.info("OrderOutboxQueue shutdown initiated, queueSize={}", queue.size());
        shuttingDown = true;
    }

    /**
     * Shutdown 진행 중인지
     */
    public boolean isShuttingDown() {
        return shuttingDown;
    }
}
