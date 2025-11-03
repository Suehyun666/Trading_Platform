package com.hts.test.account;
import com.hts.generated.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.UUID;
import java.util.function.Supplier;

public final class ReserveTest {
    private static final String HOST = "localhost";//"192.168.10.1";
    private static final int PORT = 8081;

    public static void main(String[] args) {
        System.out.println("🚀 단일 시나리오 테스트 시작: Reserve 기능 예외 검증");
        ManagedChannel channel = ManagedChannelBuilder.forAddress(HOST, PORT)
                .usePlaintext().build();
        // ClientWorker 대신 Blocking Stub을 직접 사용
        var stub = AccountServiceGrpc.newBlockingStub(channel);

        // 테스트를 위한 고유 Request ID 생성
        String duplicateRequestId = UUID.randomUUID().toString();

        // [FR-01 예외] 예수금 부족: 주문가능금액 < (수량 x 가격)
        testReserveScenario("💰 FR-01: 예수금 부족",
                () -> stub.reserve(buildReserveRequest(1000L, "99999999.00", UUID.randomUUID().toString())),
                ResultCode.INSUFFICIENT_FUNDS);

        // [FR-01 예외] 유효하지 않은 요청: 금액 <= 0
        testReserveScenario("🧮 FR-01: 음수 금액 요청",
                () -> stub.reserve(buildReserveRequest(1000L, "-10.00", UUID.randomUUID().toString())),
                ResultCode.INVALID_REQUEST);

        // [FR-00 예외] 존재하지 않는 계좌: 계좌 등록 상태 아님
        testReserveScenario("❌ FR-00: 존재하지 않는 계좌",
                () -> stub.reserve(buildReserveRequest(999999L, "100.00", UUID.randomUUID().toString())),
                ResultCode.ACCOUNT_NOT_FOUND);

        // [요청 검증] 중복 RequestId (멱등성 보장 검증)
        // 1. 첫 번째 요청 (성공 가정)
        try {
            stub.reserve(buildReserveRequest(1000L, "10.00", duplicateRequestId));
            System.out.println("\n--- 중복 RequestId (1차 성공) ---");
        } catch (Exception ignored) {}

        // 2. 두 번째 요청 (DUPLICATE_REQUEST 응답 기대)
        testReserveScenario("🔁 중복 RequestId (2차 시도)",
                () -> stub.reserve(buildReserveRequest(1000L, "10.00", duplicateRequestId)),
                ResultCode.DUPLICATE_REQUEST);


        channel.shutdown();
        System.out.println("\n🎉 단일 시나리오 테스트 완료");
    }

    private static ReserveRequest buildReserveRequest(Long accountId, String amount, String requestId) {
        return ReserveRequest.newBuilder()
                .setAccountId(accountId)
                .setAmount(amount)
                .setRequestId(requestId)
                .build();
    }

    private static void testReserveScenario(String name, Supplier<ReserveReply> rpcCall, ResultCode expected) {
        System.out.printf("\n--- %s ---\n", name);
        long start = System.currentTimeMillis();
        try {
            ReserveReply reply = rpcCall.get();
            long end = System.currentTimeMillis();
            boolean passed = reply.getCode() == expected;
            String status = passed ? "✅ PASS" : "❌ FAIL";

            System.out.printf("  상태: %s\n", status);
            System.out.printf("  응답 코드: %s (예상: %s)\n", reply.getCode(), expected);
            System.out.printf("  소요 시간: %d ms\n", (end - start));

        } catch (Exception e) {
            long end = System.currentTimeMillis();
            // gRPC에서 서버의 예외가 아닌 연결/통신 오류 발생 시
            System.out.printf("  상태: ❌ UNEXPECTED RPC EXCEPTION\n");
            System.out.printf("  예외: %s\n", e.getClass().getSimpleName());
            System.out.printf("  소요 시간: %d ms\n", (end - start));
        }
    }
}