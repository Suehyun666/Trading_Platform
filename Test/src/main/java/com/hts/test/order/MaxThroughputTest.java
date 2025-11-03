package com.hts.test.order;

import com.hts.test.order.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maximum throughput test - no artificial delays
 * Tests the absolute maximum capacity of the server
 */
public final class MaxThroughputTest {
    private static final Logger log = LoggerFactory.getLogger(MaxThroughputTest.class);

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 8082;
        int clientCount = 100;
        long startAccountId = 1000;
        int durationSeconds = 10;

        log.info("Creating {} clients (accounts {}-{})...",
                clientCount, startAccountId, startAccountId + clientCount - 1);

        // Create clients
        List<OrderClient> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            long accountId = startAccountId + i;
            OrderClient client = new OrderClient(host, port, accountId);
            clients.add(client);
        }

        // Connect all clients
        log.info("Connecting {} clients...", clientCount);
        long connectStart = System.nanoTime();
        for (OrderClient client : clients) {
            client.connect();
        }
        long connectEnd = System.nanoTime();
        log.info("All clients connected in {}s", String.format("%.2f", (connectEnd - connectStart) / 1_000_000_000.0));

        // Warmup phase
        log.info("Warming up connections...");
        CountDownLatch warmupLatch = new CountDownLatch(clientCount);
        for (OrderClient client : clients) {
            new Thread(() -> {
                try {
                    client.placeOrder("AAPL", "BUY", 1L, 1L).get();
                    warmupLatch.countDown();
                } catch (Exception e) {
                    warmupLatch.countDown();
                }
            }).start();
        }
        warmupLatch.await();
        log.info("Warmup completed. Waiting 2 seconds for server to stabilize...");
        Thread.sleep(2000);

        // Create executor - use more threads for max throughput
        int threadPoolSize = Math.min(clientCount * 2, 200);
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        log.info("Using thread pool size: {} for {} clients", threadPoolSize, clientCount);

        // Statistics
        AtomicLong totalRequests = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();

        log.info("========================================");
        log.info("MAXIMUM THROUGHPUT TEST");
        log.info("Duration: {} seconds", durationSeconds);
        log.info("NO artificial delays - testing server limits!");
        log.info("========================================");

        // Start continuous load - NO DELAYS!
        long testStart = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();

        for (OrderClient client : clients) {
            Future<?> future = executor.submit(() -> {
                while (running.get()) {
                    try {
                        long startTime = System.nanoTime();
                        // Random price 1-10 cents, quantity = 1
                        long price = 1 + (long)(Math.random() * 10);
                        OrderClient.ServerResponse response = client.placeOrder("AAPL", "BUY", 1L, price).get();
                        long endTime = System.nanoTime();
                        double latencyMs = (endTime - startTime) / 1_000_000.0;

                        totalRequests.incrementAndGet();
                        latencies.add(latencyMs);

                        if (response.isError()) {
                            errorCount.incrementAndGet();
                        } else {
                            successCount.incrementAndGet();
                        }

                        // NO SLEEP - maximum throughput!
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        totalRequests.incrementAndGet();
                    }
                }
            });
            futures.add(future);
        }

        // Print progress every second
        ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger secondsElapsed = new AtomicInteger(0);
        AtomicLong lastCount = new AtomicLong(0);

        progressExecutor.scheduleAtFixedRate(() -> {
            int seconds = secondsElapsed.incrementAndGet();
            long current = totalRequests.get();
            long success = successCount.get();
            long errors = errorCount.get();
            long delta = current - lastCount.get();
            lastCount.set(current);

            log.info("[{}s] Total: {} | Success: {} | Errors: {} | Current: {}/s | Avg: {}/s",
                    seconds, current, success, errors, delta, String.format("%.2f", current / (double)seconds));
        }, 1, 1, TimeUnit.SECONDS);

        // Wait for test duration
        Thread.sleep(durationSeconds * 1000L);

        // Stop the test
        running.set(false);
        log.info("Stopping load test...");

        // Wait for all tasks to finish
        for (Future<?> future : futures) {
            try {
                future.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            }
        }

        long testEnd = System.nanoTime();
        double totalTime = (testEnd - testStart) / 1_000_000_000.0;

        progressExecutor.shutdown();
        executor.shutdown();

        // Disconnect all
        log.info("Disconnecting clients...");
        for (OrderClient client : clients) {
            client.disconnect();
        }

        // Calculate statistics
        List<Double> latencyList = new ArrayList<>(latencies);
        latencyList.sort(Double::compareTo);

        double minLatency = latencyList.isEmpty() ? 0 : latencyList.get(0);
        double maxLatency = latencyList.isEmpty() ? 0 : latencyList.get(latencyList.size() - 1);
        double avgLatency = latencyList.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p50 = latencyList.isEmpty() ? 0 : latencyList.get((int)(latencyList.size() * 0.50));
        double p95 = latencyList.isEmpty() ? 0 : latencyList.get((int)(latencyList.size() * 0.95));
        double p99 = latencyList.isEmpty() ? 0 : latencyList.get((int)(latencyList.size() * 0.99));

        // Report
        log.info("========================================");
        log.info("MAXIMUM THROUGHPUT TEST RESULTS");
        log.info("========================================");
        log.info("Test duration: {}s", String.format("%.2f", totalTime));
        log.info("Concurrent clients: {}", clientCount);
        log.info("Total requests: {}", totalRequests.get());
        log.info("Success: {}", successCount.get());
        log.info("Errors: {} ({} %)", errorCount.get(),
                String.format("%.2f", errorCount.get() * 100.0 / totalRequests.get()));
        log.info("========================================");
        log.info("MAXIMUM THROUGHPUT: {} req/sec", String.format("%.2f", totalRequests.get() / totalTime));
        log.info("========================================");
        log.info("LATENCY STATISTICS (ms)");
        log.info("----------------------------------------");
        log.info("Min:     {}", String.format("%.2f", minLatency));
        log.info("Max:     {}", String.format("%.2f", maxLatency));
        log.info("Average: {}", String.format("%.2f", avgLatency));
        log.info("P50:     {}", String.format("%.2f", p50));
        log.info("P95:     {}", String.format("%.2f", p95));
        log.info("P99:     {}", String.format("%.2f", p99));
        log.info("========================================");

        // Print histogram
        printHistogram(latencyList);
    }

    private static void printHistogram(List<Double> latencies) {
        if (latencies.isEmpty()) return;

        log.info("LATENCY HISTOGRAM");
        log.info("----------------------------------------");

        double min = latencies.get(0);
        double max = latencies.get(latencies.size() - 1);
        int buckets = 10;
        double bucketSize = (max - min) / buckets;

        if (bucketSize == 0) {
            bucketSize = 1.0;
        }

        int[] counts = new int[buckets];
        for (double latency : latencies) {
            int bucket = (int)((latency - min) / bucketSize);
            if (bucket >= buckets) bucket = buckets - 1;
            counts[bucket]++;
        }

        int maxCount = 0;
        for (int count : counts) {
            if (count > maxCount) maxCount = count;
        }

        for (int i = 0; i < buckets; i++) {
            double rangeStart = min + i * bucketSize;
            double rangeEnd = min + (i + 1) * bucketSize;
            int count = counts[i];
            int barLength = maxCount == 0 ? 0 : (int)((count * 50.0) / maxCount);
            String bar = "█".repeat(barLength);

            log.info("{} - {} ms: {} ({})",
                    String.format("%6.2f", rangeStart),
                    String.format("%6.2f", rangeEnd),
                    bar,
                    count);
        }
        log.info("========================================");
    }
}
