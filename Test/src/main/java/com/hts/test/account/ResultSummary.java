package com.hts.test.account;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public record ResultSummary(double avg, double p50, double p95, double p99,
                            double max, int success, int fail, double tps) {
    public static ResultSummary from(List<CompletableFuture<Double>> futures, double sec) {
        List<Double> lat = futures.stream().map(f -> {
            try { return f.get(); } catch(Exception e) { return -1d; }
        }).filter(l -> l > 0).sorted().toList();
        int n = lat.size();
        double avg = lat.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double p50 = lat.get((int)(n*0.5));
        double p95 = lat.get((int)(n*0.95));
        double p99 = lat.get((int)(n*0.99));
        double max = lat.get(n-1);
        double tps = n / sec;
        return new ResultSummary(avg,p50,p95,p99,max,n,0,tps);
    }

    public void print() {
        System.out.printf("""
        \n=== PERFORMANCE REPORT ===
        Clients: %d  |  TPS: %.1f/s
        Avg=%.2f ms  P50=%.2f ms  P95=%.2f ms  P99=%.2f ms  Max=%.2f ms
        """, success, tps, avg, p50, p95, p99, max);
        LatencyHistogram.draw(p50, p95, p99, max);
    }
}
