package com.hts.order.global;

import com.hts.order.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lock-free Snowflake ID Generator (per-worker instance)
 *
 * 64-bit structure:
 * - 1 bit: unused (0)
 * - 41 bits: timestamp (ms since custom epoch)
 * - 6 bits: shard ID (0-63)
 * - 6 bits: worker ID (0-63) - unique per worker thread
 * - 12 bits: sequence (0-4095)
 *
 * 핵심 설계:
 * - 각 워커가 독립적인 OrderIdGenerator 인스턴스를 가짐
 * - workerId는 워커마다 고유하므로 ID 충돌 불가능
 * - synchronized 불필요 (단일 스레드만 접근)
 * - 성능: < 1 µs per ID generation
 */
public final class OrderIdGenerator {
    private static final Logger log = LoggerFactory.getLogger(OrderIdGenerator.class);

    private static final long CUSTOM_EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC

    private static final int TIMESTAMP_BITS = 41;
    private static final int SHARD_BITS = 6;
    private static final int WORKER_BITS = 6;
    private static final int SEQUENCE_BITS = 12;

    private static final long SHARD_MASK = (1L << SHARD_BITS) - 1;     // 0x3F (63)
    private static final long WORKER_MASK = (1L << WORKER_BITS) - 1;   // 0x3F (63)
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1; // 0xFFF (4095)

    private static final int SEQUENCE_SHIFT = 0;
    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int SHARD_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + SHARD_BITS;

    private final int shardId;
    private final int workerId;
    private final MetricsCollector metrics;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /**
     * Per-worker generator (thread-safe by isolation)
     *
     * @param shardId logical shard (0-63)
     * @param workerId unique worker ID (0-63)
     * @param metrics metrics collector
     */
    public OrderIdGenerator(int shardId, int workerId, MetricsCollector metrics) {
        if (shardId < 0 || shardId > SHARD_MASK) {
            throw new IllegalArgumentException("Shard ID must be 0-63, got: " + shardId);
        }
        if (workerId < 0 || workerId > WORKER_MASK) {
            throw new IllegalArgumentException("Worker ID must be 0-63, got: " + workerId);
        }

        this.shardId = shardId;
        this.workerId = workerId;
        this.metrics = metrics;

        log.debug("OrderIdGenerator created: shard={}, worker={}", shardId, workerId);
    }

    /**
     * Generate next order ID (lock-free, single-threaded)
     *
     * @return 64-bit unique order ID
     */
    public long nextId() {
        long currentTimestamp = System.currentTimeMillis();

        // Clock backwards detection
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            metrics.recordClockBackwards(offset);

            if (offset <= 5) {
                log.warn("Clock moved backwards by {}ms, waiting for recovery", offset);
                currentTimestamp = waitUntilTimestamp(lastTimestamp);
            } else {
                log.error("Clock moved backwards by {}ms - critical!", offset);
                throw new RuntimeException("Clock moved backwards by " + offset + "ms");
            }
        }

        if (currentTimestamp == lastTimestamp) {
            // Same millisecond - increment sequence
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // Sequence exhausted, wait for next millisecond
                currentTimestamp = waitNextMillis(currentTimestamp);
            }
        } else {
            // New millisecond - reset sequence
            sequence = 0;
        }

        lastTimestamp = currentTimestamp;

        long ts = currentTimestamp - CUSTOM_EPOCH;
        return (ts << TIMESTAMP_SHIFT)
                | ((long) shardId << SHARD_SHIFT)
                | ((long) workerId << WORKER_SHIFT)
                | sequence;
    }

    /**
     * Extract shard ID from order ID
     */
    public static int extractShard(long orderId) {
        if (isExternal(orderId)) {
            return Math.abs((int) (orderId % 64));
        }
        return (int) ((orderId >> SHARD_SHIFT) & SHARD_MASK);
    }

    /**
     * Check if order ID is external (negative)
     */
    public static boolean isExternal(long orderId) {
        return orderId < 0;
    }

    /**
     * Get logical shard count
     */
    public static int getLogicalShardCount() {
        return (int) (SHARD_MASK + 1); // 64
    }

    private long waitNextMillis(long currentTimestamp) {
        while (currentTimestamp == lastTimestamp) {
            Thread.onSpinWait(); // CPU hint for spin-wait
            currentTimestamp = System.currentTimeMillis();
        }
        return currentTimestamp;
    }

    private long waitUntilTimestamp(long targetTimestamp) {
        long current = System.currentTimeMillis();
        while (current < targetTimestamp) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for clock recovery", e);
            }
            current = System.currentTimeMillis();
        }
        return current;
    }
}
