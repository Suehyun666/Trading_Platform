package com.hts.test.order.concurrent;

import com.hts.test.order.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractConcurrentOrderTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractConcurrentOrderTest.class);

    protected final String host = "localhost";
    protected final int port = 8082;
    protected final int clientCount = 1000;
    protected final long startAccountId = 1000;

    /**
     * 핵심: 하위 클래스가 자신의 심볼 선택 전략을 구현하도록 강제하는 추상 메서드.
     * @return 부하를 발생시킬 주문 심볼 (String)
     */
    protected abstract String selectSymbol();

    /**
     * 테스트 이름을 반환하여 로그에 출력합니다.
     */
    protected String getStrategyName() {
        return this.getClass().getSimpleName().replace("ConcurrentTest", "");
    }

    private record LatencyResult(OrderClient.ServerResponse response, double latencyMs) {}

    public final void runTest() throws Exception {
        log.info("Starting {}...", this.getClass().getSimpleName());

        // 1. 클라이언트 생성
        List<OrderClient> clients = createAndConnectClients();

        // 2. 워밍업
        warmupClients(clients);

        // 3. 동시성 요청 준비
        log.info("Preparing to send orders with {} strategy...", getStrategyName());
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(clientCount);
        List<CompletableFuture<LatencyResult>> futures = new ArrayList<>(clientCount);

        log.info("3..."); Thread.sleep(1000);
        log.info("2..."); Thread.sleep(1000);
        log.info("1..."); Thread.sleep(1000);
        log.info("GO! Sending {} orders simultaneously!", clientCount);

        // 4. 주문 요청 퓨처 생성
        long sendStart = System.nanoTime();
        for (OrderClient client : clients) {
            CompletableFuture<LatencyResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    latch.await();
                    long startTime = System.nanoTime();

                    // 🌟 추상 메서드 호출: 하위 클래스의 심볼 선택 전략 사용
                    String symbol = selectSymbol();

                    long price = 1 + (long)(Math.random() * 10);
                    OrderClient.ServerResponse response = client.placeOrder(symbol, "BUY", 1L, price).get();
                    long endTime = System.nanoTime();
                    double latencyMs = (endTime - startTime) / 1_000_000.0;
                    return new LatencyResult(response, latencyMs);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            futures.add(future);
        }

        // 5. 동시에 릴리즈
        latch.countDown();

        // 6. 결과 수집 및 보고서 생성
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        long sendEnd = System.nanoTime();
        double totalTime = (sendEnd - sendStart) / 1_000_000_000.0;

        generateReport(totalTime, futures, executor, clients);
    }

    private List<OrderClient> createAndConnectClients() throws Exception {
        log.info("Creating {} clients (accounts {}-{})...",
                clientCount, startAccountId, startAccountId + clientCount - 1);
        List<OrderClient> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            long accountId = startAccountId + i;
            OrderClient client = new OrderClient(host, port, accountId);
            clients.add(client);
        }
        log.info("Connecting {} clients...", clientCount);
        for (OrderClient client : clients) {
            client.connect();
        }
        return clients;
    }

    private void warmupClients(List<OrderClient> clients) throws InterruptedException {
        log.info("Warming up connections with dummy requests...");
        CountDownLatch warmupLatch = new CountDownLatch(clients.size());
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
        log.info("Warmup completed. Server and connections are ready.");
        Thread.sleep(1000);
    }

    private void generateReport(double totalTime, List<CompletableFuture<LatencyResult>> futures,
                                ExecutorService executor, List<OrderClient> clients) {

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<Double> latencies = new ArrayList<>();

        for (CompletableFuture<LatencyResult> future : futures) {
            try {
                LatencyResult result = future.get();
                latencies.add(result.latencyMs);

                if (result.response.isError()) {
                    errorCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            }
        }

        latencies.sort(Double::compareTo);

        // 통계 계산 로직 (ConcurrentOrderTest.java의 로직 재활용)
        double p50 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.50));
        double p95 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.95));
        double p99 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.99));

        executor.shutdown();
        for (OrderClient client : clients) client.disconnect();

        // Report
        log.info("========================================");
        log.info("{} CONCURRENT TEST RESULTS", getStrategyName().toUpperCase());
        log.info("========================================");
        log.info("Total clients: {}", clientCount);
        log.info("Total time: {}s (Burst Time)", String.format("%.2f", totalTime));
        log.info("Throughput (Calculated Burst): {} orders/sec", String.format("%.2f", clientCount / totalTime));
        log.info("----------------------------------------");
        log.info("Success: {}", successCount.get());
        log.info("Errors: {}", errorCount.get());
        log.info("========================================");
        log.info("LATENCY STATISTICS (ms)");
        log.info("----------------------------------------");
        log.info("P50:     {}", String.format("%.2f", p50));
        log.info("P95:     {}", String.format("%.2f", p95));
        log.info("P99:     {}", String.format("%.2f", p99));
        log.info("========================================");
    }
}