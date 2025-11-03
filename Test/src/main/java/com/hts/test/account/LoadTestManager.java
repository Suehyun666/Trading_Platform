// LoadTestManager.java (GrpcLoadTester.java 변경)
package com.hts.test.account;

import com.hts.test.account.client.GrpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LoadTestManager { // 클래스 이름 변경
    private static final String HOST = "localhost";//"192.168.10.1";
    private static final int PORT = 8081;
    private static final int CLIENTS = 1001;

    public static void main(String[] args) throws Exception {
        TestMetrics metrics = new TestMetrics(); // TestMetrics로 변경
        ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);

        System.out.println("▶ Creating " + CLIENTS + " clients...");
        List<GrpcClient> clients = new ArrayList<>(); // GrpcClient로 변경
        for (int i = 0; i < CLIENTS; i++) {
            clients.add(new GrpcClient(HOST, PORT)); // ID 제거
        }
        System.out.println("✓ Created " + clients.size() + " clients");

        // Warmup - Connection pool & DB warmup
        System.out.println("▶ Warming up (activating connections & DB pool)...");
        CountDownLatch warmup = new CountDownLatch(clients.size());
        for (GrpcClient c : clients) pool.execute(() -> { c.activate(); warmup.countDown(); });
        warmup.await();

        System.out.println("▶ Additional warmup (150 requests)...");
        CountDownLatch warmup2 = new CountDownLatch(150);
        for (int i = 0; i < 150; i++) {
            int idx = i;
            pool.execute(() -> {
                try {
                    clients.get(idx % clients.size()).activate();
                } finally {
                    warmup2.countDown();
                }
            });
        }
        warmup2.await();

        Thread.sleep(1000);
        System.out.println("✓ Warmup done\n");

        // Run test scenarios
        ClientPool clientPool = new ClientPool(clients); // ClientPool로 변경
        ScenarioRunner scenarios = new ScenarioRunner(metrics, clientPool, 1000); // ScenarioRunner로 변경
        scenarios.runAll();
        scenarios.shutdown(); // ScenarioRunner 종료

        // 모든 GRPC 채널 종료
        for (GrpcClient c : clients) c.shutdown();

        pool.shutdownNow();
    }
}