package com.hts.test.order;

import com.hts.test.order.client.OrderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Asynchronous Maximum Throughput Load Test
 */
public final class MaxThroughputTestAsync {
    private static final Logger log = LoggerFactory.getLogger(MaxThroughputTestAsync.class);

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 8082;
        int clientCount = 100;
        long startAccountId = 1000;
        int durationSeconds = 10;
        int IN_FLIGHT_PER_CLIENT = 10; // 동시 요청 유지 수

        log.info("Creating {} clients...", clientCount);
        List<OrderClient> clients = new ArrayList<>(clientCount);
        for (int i = 0; i < clientCount; i++) {
            long accountId = startAccountId + i;
            OrderClient client = new OrderClient(host, port, accountId);
            client.connect();
            clients.add(client);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong total = new AtomicLong();
        AtomicLong success = new AtomicLong();
        AtomicLong error = new AtomicLong();
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();

        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger sec = new AtomicInteger(0);
        AtomicLong last = new AtomicLong(0);

        progress.scheduleAtFixedRate(() -> {
            int s = sec.incrementAndGet();
            long cur = total.get();
            long delta = cur - last.get();
            last.set(cur);
            double avg = cur / (double) s;
            log.info("[{}s] total={} ok={} err={} current={}/s avg={}/s",
                    s, cur, success.get(), error.get(), delta, String.format("%.2f", avg));
        }, 1, 1, TimeUnit.SECONDS);

        log.info("Running async load test for {} seconds ...", durationSeconds);

        // 부하 시작
        for (OrderClient client : clients) {
            for (int i = 0; i < IN_FLIGHT_PER_CLIENT; i++) {
                sendAsync(client, running, total, success, error, latencies);
            }
        }

        // duration 만큼만 실행
        Thread.sleep(durationSeconds * 1000L);
        running.set(false);
        log.info("Stopping test...");
        Thread.sleep(1000); // 남아있는 콜백 조금 기다리기

        progress.shutdownNow();
        for (OrderClient c : clients) c.disconnect();

        // === 결과 정리 ===
        List<Double> list = new ArrayList<>(latencies);
        list.sort(Double::compareTo);

        double min = list.isEmpty() ? 0 : list.get(0);
        double max = list.isEmpty() ? 0 : list.get(list.size() - 1);
        double avg = list.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double p50 = percentile(list, 50);
        double p95 = percentile(list, 95);
        double p99 = percentile(list, 99);

        double throughput = total.get() / (double) durationSeconds;

        log.info("========================================");
        log.info("ASYNC THROUGHPUT TEST RESULTS");
        log.info("========================================");
        log.info("Total requests: {}", total.get());
        log.info("Success: {}", success.get());
        log.info("Errors: {}", error.get());
        log.info("Throughput: {} req/s", String.format("%.2f", throughput));
        log.info("----------------------------------------");
        log.info("Latency (ms)");
        log.info("Min:     {}", String.format("%.2f", min));
        log.info("Max:     {}", String.format("%.2f", max));
        log.info("Avg:     {}", String.format("%.2f", avg));
        log.info("P50:     {}", String.format("%.2f", p50));
        log.info("P95:     {}", String.format("%.2f", p95));
        log.info("P99:     {}", String.format("%.2f", p99));
        log.info("========================================");

        printHistogramDynamic(list);
    }

    private static void sendAsync(OrderClient client,
                                  AtomicBoolean running,
                                  AtomicLong total,
                                  AtomicLong success,
                                  AtomicLong error,
                                  ConcurrentLinkedQueue<Double> latencies) {
        if (!running.get()) return;

        long startNs = System.nanoTime();
        long price = 1 + (long) (Math.random() * 10);

        client.placeOrder("AAPL", "BUY", 1L, price)
                .whenComplete((resp, ex) -> {
                    long endNs = System.nanoTime();
                    double latencyMs = (endNs - startNs) / 1_000_000.0;
                    latencies.add(latencyMs);
                    total.incrementAndGet();

                    if (ex != null || resp == null || resp.isError()) {
                        error.incrementAndGet();
                    } else {
                        success.incrementAndGet();
                    }

                    if (running.get()) {
                        // 다시 발행해서 in-flight 유지
                        sendAsync(client, running, total, success, error, latencies);
                    }
                });
    }

    private static double percentile(List<Double> data, double pct) {
        if (data.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct / 100.0 * data.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= data.size()) idx = data.size() - 1;
        return data.get(idx);
    }

    /**
     * 분위 기반 + 중복 경계 제거 + 실제 값 출력
     */
    private static void printHistogramDynamic(List<Double> latencies) {
        if (latencies.isEmpty()) return;

        int n = latencies.size();
        // 데이터 적으면 그냥 단일 구간
        if (n < 20) {
            log.info("LATENCY HISTOGRAM (all) 0 - {} ms: {} ({} samples)",
                    String.format("%.2f", latencies.get(n - 1)),
                    "█".repeat(10),
                    n);
            return;
        }

        // 분위수 뽑기
        double[] rawBounds = new double[]{
                latencies.get((int) (n * 0.01)),
                latencies.get((int) (n * 0.05)),
                latencies.get((int) (n * 0.10)),
                latencies.get((int) (n * 0.25)),
                latencies.get((int) (n * 0.50)),
                latencies.get((int) (n * 0.75)),
                latencies.get((int) (n * 0.90)),
                latencies.get((int) (n * 0.95)),
                latencies.get((int) (n * 0.99)),
                latencies.get(n - 1)
        };

        // 같은 값으로 뭉친 분위수는 버리기 (예: 전부 12ms일 때)
        List<Double> boundsList = new ArrayList<>();
        double last = -1;
        for (double b : rawBounds) {
            if (boundsList.isEmpty() || Math.abs(b - last) > 0.0001) {
                boundsList.add(b);
                last = b;
            }
        }
        double[] bounds = boundsList.stream().mapToDouble(d -> d).toArray();
        int[] counts = new int[bounds.length];

        for (double l : latencies) {
            for (int i = 0; i < bounds.length; i++) {
                if (l <= bounds[i]) {
                    counts[i]++;
                    break;
                }
            }
        }

        int maxCount = Arrays.stream(counts).max().orElse(1);

        log.info("LATENCY HISTOGRAM");
        log.info("----------------------------------------");
        for (int i = 0; i < bounds.length; i++) {
            double start = (i == 0) ? 0.0 : bounds[i - 1];
            double end = bounds[i];
            int count = counts[i];
            int barLen = (int) ((count * 50.0) / maxCount);
            String bar = (barLen == 0) ? "" : "█".repeat(barLen);
            log.info("{} - {} ms: {} ({})",
                    String.format("%6.2f", start),
                    String.format("%6.2f", end),
                    bar,
                    count);
        }
        log.info("========================================");
    }
}
