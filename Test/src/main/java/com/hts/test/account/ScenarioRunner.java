package com.hts.test.account;

import com.hts.generated.grpc.ResultCode;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 다양한 reserve/unreserve 테스트 케이스를 자동 실행하고
 * 결과를 MetricsCollector로 전달.
 */
public final class ScenarioRunner {

    private final TestMetrics metrics;
    private final ClientPool grpcClient;
    private final int threadCount;
    private final ExecutorService pool;

    public ScenarioRunner(TestMetrics metrics, ClientPool grpcClient, int threadCount) {
        this.metrics = metrics;
        this.grpcClient = grpcClient;
        this.threadCount = threadCount;
        this.pool = Executors.newFixedThreadPool(threadCount);
    }

    public void runAll() throws InterruptedException {
        runScenario("✅ Normal Reserve", this::testNormalReserve);
        runScenario("💰 Insufficient Funds", this::testInsufficientFunds);
        runScenario("🧮 Negative Amount", this::testNegativeAmount);
        runScenario("🔁 Duplicate RequestId", this::testDuplicateRequest);
        runScenario("❌ Invalid AccountId", this::testInvalidAccount);
        runScenario("🧩 Consistency & Mixed Scenarios", this::testConsistencyCheck);
    }

    private void runScenario(String name, Runnable testMethod) throws InterruptedException {
        System.out.printf("\n=== Scenario: %s ===\n", name);
        long start = System.nanoTime();
        testMethod.run();
        long end = System.nanoTime();
        metrics.report(name, (end - start) / 1_000_000);
        Thread.sleep(500);
    }

    /** 정상 예수금 예약 */
    private void testNormalReserve() {
        runConcurrent(1000, i -> {
            grpcClient.reserve(1000L + i, BigDecimal.valueOf(100), UUID.randomUUID().toString());
            grpcClient.unreserve(1000L + i, BigDecimal.valueOf(100), UUID.randomUUID().toString());
        });
    }

    /** 예수금 부족 */
    private void testInsufficientFunds() {
        runConcurrent(1000, i -> {
            grpcClient.reserve(2000L + i, BigDecimal.valueOf(99999999), UUID.randomUUID().toString());
        });
    }

    /** 음수 금액 */
    private void testNegativeAmount() {
        runConcurrent(1000, i -> {
            grpcClient.reserve(3000L + i, BigDecimal.valueOf(-100), UUID.randomUUID().toString());
        });
    }

    /** 중복 requestId */
    private void testDuplicateRequest() {
        String rid = UUID.randomUUID().toString();
        runConcurrent(1000, i -> {
            grpcClient.reserve(4000L + i, BigDecimal.valueOf(50), rid);
        });
    }

    /** 존재하지 않는 계좌 */
    private void testInvalidAccount() {
        runConcurrent(1000, i -> {
            grpcClient.reserve(999999L + i, BigDecimal.valueOf(100), UUID.randomUUID().toString());
        });
    }

    private void testConsistencyCheck() {
        int userCount = 1000;
        CountDownLatch latch = new CountDownLatch(userCount);

        for (int i = 0; i < userCount; i++) {
            Long accountId = 1000L + i;
            pool.submit(() -> {
                long start = System.nanoTime();
                String ridBase = UUID.randomUUID().toString();

                try {
                    ResultCode r1 = grpcClient.reserve(accountId, BigDecimal.valueOf(1000), ridBase + "-R1").getCode();
                    ResultCode u1 = grpcClient.unreserve(accountId, BigDecimal.valueOf(500), ridBase + "-U1").getCode();
                    ResultCode u2 = grpcClient.unreserve(accountId, BigDecimal.valueOf(1000), ridBase + "-U2").getCode();

                    long elapsed = System.nanoTime() - start;

                    if (r1 == ResultCode.SUCCESS && u1 == ResultCode.SUCCESS && u2 == ResultCode.INSUFFICIENT_FUNDS) {
                        metrics.recordSuccess(elapsed);
                        metrics.recordSuccess(elapsed);
                        metrics.recordSuccess(elapsed);
                    } else {
                        String errorMsg = String.format(
                            "accountId=%d | R1=%s U1=%s U2=%s | Expected: R1=SUCCESS U1=SUCCESS U2=INSUFFICIENT_FUNDS",
                            accountId, r1, u1, u2
                        );
                        metrics.recordFailure(elapsed, new RuntimeException(errorMsg));

                        if (r1 != ResultCode.SUCCESS) {
                            metrics.recordFailure(0, new RuntimeException("R1 failed: " + r1));
                        }
                        if (u1 != ResultCode.SUCCESS) {
                            metrics.recordFailure(0, new RuntimeException("U1 failed: " + u1));
                        }
                    }
                } catch (Exception e) {
                    metrics.recordFailure(System.nanoTime() - start, e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
    }

    private void runConcurrent(int count, Consumer<Integer> task) {
        CountDownLatch latch = new CountDownLatch(count);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            pool.submit(() -> {
                long start = System.nanoTime();
                try {
                    task.accept(idx);
                    metrics.recordSuccess(System.nanoTime() - start);
                } catch (Exception e) {
                    metrics.recordFailure(System.nanoTime() - start, e);
                } finally {
                    latch.countDown();
                }
            });
        }
        try { latch.await(); } catch (InterruptedException ignored) {}
    }

    /** QAS-P3: 예수금 예약/해제 교차 테스트 */
    public void testReserveUnreserveCross(int concurrentUsers) {
        // 목표: 1000명 동시 접속 상태에서 Reserve/Unreserve를 교차하며 실행
        runConcurrent(concurrentUsers * 10, i -> { // 각 사용자당 10회씩 요청
            // accountId는 100000L부터 시작하도록 PerformanceLoadTest에서 설정 가정
            Long accountId = 100000L + (i % concurrentUsers);
            BigDecimal amount = BigDecimal.valueOf(100);

            // Reserve 후 Unreserve를 바로 실행하여 자원 상태 변화를 유발
            String reserveId = UUID.randomUUID().toString();
            grpcClient.reserve(accountId, amount, reserveId);

            String unreserveId = UUID.randomUUID().toString();
            grpcClient.unreserve(accountId, amount, unreserveId);

        });
    }


    /** 스레드 풀을 종료하고 모든 작업이 완료되기를 기다립니다. */
    public void shutdown() {
        pool.shutdown();
        try {
            // 최대 60초 동안 종료를 기다립니다.
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("TestScenarios pool did not terminate in time. Forcing shutdown.");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
