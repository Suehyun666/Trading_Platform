    package com.hts.test.order;

    import com.hts.test.order.client.OrderClient;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    import java.util.ArrayList;
    import java.util.List;
    import java.util.concurrent.*;
    import java.util.concurrent.atomic.AtomicInteger;

    public final class ConcurrentOrderTest {
        private static final Logger log = LoggerFactory.getLogger(ConcurrentOrderTest.class);

        public static void main(String[] args) throws Exception {
            String host = "localhost";
            int port = 8082;
            int clientCount = 1000;
            long startAccountId = 1000;

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
            log.info("All clients connected in {:.2f}s", (connectEnd - connectStart) / 1_000_000_000.0);

            // Warmup phase
            log.info("Warming up connections with dummy requests...");
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
            log.info("Warmup completed. Server and connections are ready.");
            Thread.sleep(1000);

            // Countdown
            log.info("Preparing to send orders...");
            CountDownLatch latch = new CountDownLatch(1);

            log.info("3...");
            Thread.sleep(1000);
            log.info("2...");
            Thread.sleep(1000);
            log.info("1...");
            Thread.sleep(1000);
            log.info("GO! Sending {} orders simultaneously!", clientCount);

            // Create dedicated executor with enough threads
            ExecutorService executor = Executors.newFixedThreadPool(clientCount);

            // Send orders concurrently with latency tracking
            long sendStart = System.nanoTime();
            List<CompletableFuture<LatencyResult>> futures = new ArrayList<>(clientCount);

            for (OrderClient client : clients) {
                CompletableFuture<LatencyResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        latch.await();
                        long startTime = System.nanoTime();
                        // 1~10 cents random price, quantity = 1
                        long price = 1 + (long)(Math.random() * 10);
                        OrderClient.ServerResponse response = client.placeOrder("AAPL", "BUY", 1L, price).get();
                        long endTime = System.nanoTime();
                        double latencyMs = (endTime - startTime) / 1_000_000.0;
                        return new LatencyResult(response, latencyMs);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
                futures.add(future);
            }

            // Release all at once
            latch.countDown();

            // Wait for all responses
            log.info("Waiting for all responses...");
            CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            allDone.join();

            long sendEnd = System.nanoTime();
            double totalTime = (sendEnd - sendStart) / 1_000_000_000.0;

            // Collect statistics
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger insufficientBalanceCount = new AtomicInteger(0);
            AtomicInteger invalidSessionCount = new AtomicInteger(0);
            AtomicInteger otherErrorCount = new AtomicInteger(0);
            List<Double> latencies = new ArrayList<>();

            for (CompletableFuture<LatencyResult> future : futures) {
                try {
                    LatencyResult result = future.get();
                    OrderClient.ServerResponse response = result.response;
                    latencies.add(result.latencyMs);

                    if (response.isError()) {
                        errorCount.incrementAndGet();
                        if (response.errorCode() == 400) {
                            insufficientBalanceCount.incrementAndGet();
                        } else if (response.errorCode() == 401) {
                            invalidSessionCount.incrementAndGet();
                        } else {
                            otherErrorCount.incrementAndGet();
                        }
                    } else {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    otherErrorCount.incrementAndGet();
                }
            }

            // Calculate latency statistics
            latencies.sort(Double::compareTo);
            double minLatency = latencies.isEmpty() ? 0 : latencies.get(0);
            double maxLatency = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
            double avgLatency = latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.50));
            double p95 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.95));
            double p99 = latencies.isEmpty() ? 0 : latencies.get((int)(latencies.size() * 0.99));

            // Shutdown executor
            executor.shutdown();

            // Disconnect all
            log.info("Disconnecting clients...");
            for (OrderClient client : clients) {
                client.disconnect();
            }

            // Report
            log.info("========================================");
            log.info("CONCURRENT ORDER TEST RESULTS");
            log.info("========================================");
            log.info("Total clients: {}", clientCount);
            log.info("Total time: {}s", String.format("%.2f", totalTime));
            log.info("Throughput: {} orders/sec", String.format("%.2f", clientCount / totalTime));
            log.info("----------------------------------------");
            log.info("Success: {}", successCount.get());
            log.info("Errors: {}", errorCount.get());
            log.info("  - Insufficient balance: {}", insufficientBalanceCount.get());
            log.info("  - Invalid session: {}", invalidSessionCount.get());
            log.info("  - Other errors: {}", otherErrorCount.get());
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
            printHistogram(latencies);
        }

        private static void printHistogram(List<Double> latencies) {
            if (latencies.isEmpty()) return;

            log.info("LATENCY HISTOGRAM");
            log.info("----------------------------------------");

            double min = latencies.get(0);
            double max = latencies.get(latencies.size() - 1);
            int buckets = 10;
            double bucketSize = (max - min) / buckets;

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

        private record LatencyResult(OrderClient.ServerResponse response, double latencyMs) {}
    }
