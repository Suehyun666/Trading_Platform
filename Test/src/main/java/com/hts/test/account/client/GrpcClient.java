package com.hts.test.account.client;

import com.hts.generated.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.math.BigDecimal;

public class GrpcClient {
    private final AccountServiceGrpc.AccountServiceBlockingStub stub;
    private final ManagedChannel channel;

    public GrpcClient(String host, int port) { // ID 필드 제거, 팩토리에서 관리
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = AccountServiceGrpc.newBlockingStub(channel);
    }

    // Warmup 메서드는 테스트 시나리오 로직이므로 제거하거나 외부에서 처리하도록 간소화
    // 대신, 단순히 채널을 활성화하는 역할을 남길 수 있습니다.
    public void activate() {
        // 간단한 호출로 채널 활성화만 유도
        try {
            stub.reserve(ReserveRequest.newBuilder()
                    .setAccountId(1)
                    .setAmount("0.00")
                    .setRequestId("WARMUP")
                    .build());
        } catch (Exception ignored) {}
    }

    /** 예수금 예약 RPC 호출 */
    public ReserveReply reserve(Long accountId, BigDecimal amount, String requestId) {
        ReserveRequest req = ReserveRequest.newBuilder()
                .setAccountId(accountId)
                .setAmount(amount.toPlainString())
                .setRequestId(requestId)
                .build();
        return stub.reserve(req);
    }

    /** 예약 해제 RPC 호출 */
    public UnreserveReply unreserve(Long accountId, BigDecimal amount, String requestId) {
        UnreserveRequest req = UnreserveRequest.newBuilder()
                .setAccountId(accountId)
                .setAmount(amount.toPlainString())
                .setRequestId(requestId)
                .build();
        return stub.unreserve(req);
    }

    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }
}