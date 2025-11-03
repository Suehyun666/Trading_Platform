// 파일명: PerformanceLoadTest.java

package com.hts.test.account;

import com.hts.test.account.client.GrpcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PerformanceLoadTest {
    private static final int CLIENTS = 1000; // QAS-P3 목표: 1000명의 동시 접속 사용자
    private static final int THREADS = 100; // TestScenarios의 스레드 풀 크기

    public static void main(String[] args) throws Exception {
        TestMetrics metrics = new TestMetrics();
        ExecutorService workerPool = Executors.newFixedThreadPool(CLIENTS);

        System.out.println("▶ Creating " + CLIENTS + " clients...");
        List<GrpcClient> workers = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            workers.add(new GrpcClient("192.168.10.1", 8081));
        }
        System.out.println("✓ Created " + workers.size() + " clients");

        // Warmup
        CountDownLatch warmup = new CountDownLatch(workers.size());
        for (GrpcClient w : workers) workerPool.execute(() -> { w.activate(); warmup.countDown(); });
        warmup.await();
        System.out.println("✓ Warmup done");

        // TestScenarios 인스턴스 생성 및 실행
        ClientPool grpcClient = new ClientPool(workers);
        // TestScenarios는 별도의 내부 풀(THREADS 크기)을 사용하여 테스트를 실행
        ScenarioRunner scenarios = new ScenarioRunner(metrics, grpcClient, THREADS);

        // QAS-P3 검증: 예수금 예약/해제 교차 테스트 실행 (1000 * 10 = 10000 트랜잭션)
        System.out.println("\n🚀 QAS-P3 목표: 1000 TPS 검증 (Reserve/Unreserve 교차)");
        long startTime = System.currentTimeMillis();
        scenarios.testReserveUnreserveCross(CLIENTS);
        long endTime = System.currentTimeMillis();

        // 최종 리포트 출력
        metrics.report("QAS-P3 Load Test (1000 Clients)", endTime - startTime);

        // [문제 해결] TestScenarios 스레드 풀 종료
        scenarios.shutdown();

        // ClientWorker 풀 종료 (이것도 중요)
        workerPool.shutdownNow();
    }
}