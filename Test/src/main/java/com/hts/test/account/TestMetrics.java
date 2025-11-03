package com.hts.test.account;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class TestMetrics {
    private final List<Long> latencies = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> errorTypes = new ConcurrentHashMap<>();
    private final AtomicInteger successCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final List<String> failureLogs = new CopyOnWriteArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public void recordSuccess(long ns) {
        successCount.incrementAndGet();
        latencies.add(ns / 1_000_000);
    }

    public void recordFailure(long ns, Exception e) {
        failureCount.incrementAndGet();
        latencies.add(ns / 1_000_000);
        errorTypes.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();

        String logEntry = String.format("[%s] %s", LocalDateTime.now().format(formatter), e.getMessage());
        failureLogs.add(logEntry);
    }

    public void report(String scenario, long ms) {
        double p50 = percentile(50);
        double p95 = percentile(95);
        double p99 = percentile(99);
        double avg = latencies.stream().mapToDouble(Long::doubleValue).average().orElse(0);
        int totalRequests = successCount.get() + failureCount.get();
        double errorRate = (failureCount.get() * 100.0) / totalRequests;
        double consistencyAccuracy = (successCount.get() * 100.0) / totalRequests;
        System.out.printf(
                """
                [Report: %s]
                Total: %d, Success: %d, Fail: %d
                ErrorRate: %.2f%%  |  Consistency Accuracy: %.2f%%
                Avg=%.2fms, P50=%.2fms, P95=%.2fms, P99=%.2fms
                Duration=%.2fms
                """,
                scenario, totalRequests, successCount.get(), failureCount.get(),
                errorRate, consistencyAccuracy,
                avg, p50, p95, p99, (double) ms
        );
        drawHistogram();
        if (!errorTypes.isEmpty()) System.out.println("Error types: " + errorTypes);

        if (!failureLogs.isEmpty()) {
            writeFailureLog(scenario);
        }
    }

    private void writeFailureLog(String scenario) {
        String filename = "test-failures-" + System.currentTimeMillis() + ".log";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, true))) {
            writer.write("========================================\n");
            writer.write("Scenario: " + scenario + "\n");
            writer.write("Timestamp: " + LocalDateTime.now().format(formatter) + "\n");
            writer.write("Total Failures: " + failureCount.get() + "\n");
            writer.write("========================================\n\n");

            for (String log : failureLogs) {
                writer.write(log + "\n");
            }
            writer.write("\n");
            System.out.println("Failure log written to: " + filename);
        } catch (IOException e) {
            System.err.println("Failed to write failure log: " + e.getMessage());
        }
    }

    private double percentile(double p) {
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(idx, 0));
    }

    private void drawHistogram() {
        int[] bins = new int[10];
        for (long l : latencies) bins[Math.min((int)(l/25), 9)]++;
        System.out.println("Latency Histogram:");
        for (int i=0;i<bins.length;i++) {
            System.out.printf("%3d~%3dms | %s\n", i*25, (i+1)*25, "█".repeat(bins[i]/2));
        }
    }
}

