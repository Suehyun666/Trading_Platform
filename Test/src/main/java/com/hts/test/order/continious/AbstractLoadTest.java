package com.hts.test.order.continious;

import com.hts.test.order.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractLoadTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractLoadTest.class);

    protected final String host = "localhost";
    protected final int port = 8082;
    protected final int durationSeconds = 10;

    /**
     * 핵심: 하위 클래스가 자신의 심볼 선택 전략을 구현하도록 강제하는 추상 메서드.
     * @return 부하를 발생시킬 주문 심볼 (String)
     */
    protected abstract String selectSymbol();

    public final void runTest(int clientCount, long startAccountId) throws Exception {
        log.info("Starting {}...", this.getClass().getSimpleName());

        // 1. 클라이언트 생성 및 연결
        log.info("Creating {} clients (accounts {}-{})...",
                clientCount, startAccountId, startAccountId + clientCount - 1);
        List<OrderClient> clients = createAndConnectClients(host, port, clientCount, startAccountId);

        // 2. 워밍업
        warmupClients(clients);

        // 3. Executor 설정
        int threadPoolSize = Math.min(clientCount, Runtime.getRuntime().availableProcessors() * 4);
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
        log.info("Using thread pool size: {} for {} clients", threadPoolSize, clientCount);

        // 4. Statistics 설정
        AtomicLong totalRequests = new AtomicLong(0);
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicBoolean running = new AtomicBoolean(true);
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();

        log.info("Starting continuous load test for {} seconds with **{}** strategy...",
                durationSeconds, getStrategyName());

        // 5. 부하 발생 루프 (주요 로직)
        long testStart = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();

        for (OrderClient client : clients) {
            Future<?> future = executor.submit(() -> {
                while (running.get()) {
                    try {
                        String symbol = selectSymbol(); // 🌟 하위 클래스에서 정의된 심볼 선택 전략 호출

                        long startTime = System.nanoTime();
                        long price = 1 + (long)(Math.random() * 10);
                        OrderClient.ServerResponse response = client.placeOrder(symbol, "BUY", 1L, price).get();
                        long endTime = System.nanoTime();
                        double latencyMs = (endTime - startTime) / 1_000_000.0;

                        totalRequests.incrementAndGet();
                        latencies.add(latencyMs);

                        if (response.isError()) {
                            errorCount.incrementAndGet();
                        } else {
                            successCount.incrementAndGet();
                        }
                        Thread.sleep(1); // Small delay
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                        totalRequests.incrementAndGet();
                    }
                }
            });
            futures.add(future);
        }

        // 6. 실행 및 리포트
        runTestAndReport(durationSeconds, testStart, futures, running, totalRequests, successCount, errorCount, latencies, executor, clients);
    }

    /**
     * 전략 이름을 반환하여 로그에 출력합니다.
     */
    protected String getStrategyName() {
        return this.getClass().getSimpleName().replace("LoadTest", "");
    }

    // --- 유틸리티 메서드 (ContinuousLoadTest에서 추출) ---

    private List<OrderClient> createAndConnectClients(String host, int port, int clientCount, long startAccountId) throws Exception {
        List<OrderClient> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            long accountId = startAccountId + i;
            OrderClient client = new OrderClient(host, port, accountId);
            clients.add(client);
        }

        log.info("Connecting {} clients...", clientCount);
        long connectStart = System.nanoTime();
        for (OrderClient client : clients) {
            client.connect();
        }
        long connectEnd = System.nanoTime();
        log.info("All clients connected in {}s", String.format("%.2f", (connectEnd - connectStart) / 1_000_000_000.0));
        return clients;
    }

    private void warmupClients(List<OrderClient> clients) throws InterruptedException {
        log.info("Warming up connections...");
        CountDownLatch warmupLatch = new CountDownLatch(clients.size());
        for (OrderClient client : clients) {
            new Thread(() -> {
                try {
                    // 심볼은 기본값 "AAPL"로 워밍업
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
    }

    protected void runTestAndReport(int durationSeconds, long testStart, List<Future<?>> futures, AtomicBoolean running,
                                    AtomicLong totalRequests, AtomicLong successCount, AtomicLong errorCount,
                                    ConcurrentLinkedQueue<Double> latencies, ExecutorService executor, List<OrderClient> clients) throws InterruptedException {

        // Print progress every second
        ScheduledExecutorService progressExecutor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger secondsElapsed = new AtomicInteger(0);
        progressExecutor.scheduleAtFixedRate(() -> {
            int seconds = secondsElapsed.incrementAndGet();
            long current = totalRequests.get();
            long success = successCount.get();
            long errors = errorCount.get();
            double currentThroughput = current / (double)seconds;
            log.info("[{}s] Requests: {} | Success: {} | Errors: {} | Throughput: {}/s",
                    seconds, current, success, errors, String.format("%.2f", currentThroughput));
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
            } catch (Exception ignored) {}
        }

        long testEnd = System.nanoTime();
        double totalTime = (testEnd - testStart) / 1_000_000_000.0;

        progressExecutor.shutdownNow();
        executor.shutdownNow();

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
        log.info("{} LOAD TEST RESULTS", getStrategyName().toUpperCase());
        log.info("========================================");
        log.info("Test duration: {}s", String.format("%.2f", totalTime));
        //log.info("Concurrent clients: {}", clientCount);
        log.info("Total requests: {}", totalRequests.get());
        log.info("Success: {}", successCount.get());
        log.info("Errors: {}", errorCount.get());
        log.info("Overall throughput: {} req/sec", String.format("%.2f", totalRequests.get() / totalTime));
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

    // ContinuousLoadTest에서 추출한 printHistogram 메서드도 여기에 위치
    private void printHistogram(List<Double> latencies) {
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