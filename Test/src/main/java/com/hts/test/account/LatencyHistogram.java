package com.hts.test.account;

public class LatencyHistogram {
    public static void draw(double p50, double p95, double p99, double max) {
        System.out.println("\nLatency Histogram (ms)");
        double[] bins = {p50, p95, p99, max};
        String[] labels = {"P50", "P95", "P99", "MAX"};
        for (int i = 0; i < bins.length; i++) {
            int bar = (int)(bins[i]/max*40);
            System.out.printf("%-4s | %s %.2f%n", labels[i], "█".repeat(bar), bins[i]);
        }
        System.out.println("===========================================");
    }
}

