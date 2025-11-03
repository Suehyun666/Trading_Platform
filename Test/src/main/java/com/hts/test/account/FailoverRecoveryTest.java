package com.hts.test.account;

import com.hts.generated.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class FailoverRecoveryTest {
    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 8081)
                .usePlaintext().build();
        var stub = AccountServiceGrpc.newBlockingStub(channel);

        System.out.println("💣 서버 중단 후 복구 테스트 시작");
        for (int i = 0; i < 50; i++) {
            try {
                ReserveRequest req = ReserveRequest.newBuilder()
                        .setAccountId(1000)
                        .setAmount("10.00")
                        .setRequestId(UUID.randomUUID().toString())
                        .build();
                ReserveReply reply = stub.reserve(req);
                System.out.printf("[%02d] %s\n", i, reply.getCode());
            } catch (Exception e) {
                System.err.println("Connection failed: " + e.getMessage());
            }
            TimeUnit.SECONDS.sleep(1);
        }
        channel.shutdown();
    }
}
