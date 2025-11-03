package com.hts.order.client;

import com.hts.generated.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class AccountGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(AccountGrpcClient.class);

    private final ManagedChannel channel;
    private final AccountServiceGrpc.AccountServiceBlockingStub blockingStub;

    public AccountGrpcClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = AccountServiceGrpc.newBlockingStub(channel);
        log.info("AccountGrpcClient connected to {}:{}", host, port);
    }

    /**
     * 예수금 예약 (주문 접수 시)
     * @return true if success, false if insufficient funds or other error
     */
    public boolean reserve(long accountId, BigDecimal amount) {
        String requestId = UUID.randomUUID().toString();

        ReserveRequest request = ReserveRequest.newBuilder()
                .setAccountId(accountId)
                .setAmount(amount.toPlainString())
                .setRequestId(requestId)
                .build();

        try {
            ReserveReply reply = blockingStub.reserve(request);

            if (reply.getCode() == ResultCode.SUCCESS) {
                log.debug("Reserve success: accountId={}, amount={}, requestId={}",
                        accountId, amount, requestId);
                return true;
            } else {
                log.warn("Reserve failed: accountId={}, amount={}, code={}, requestId={}",
                        accountId, amount, reply.getCode(), requestId);
                return false;
            }
        } catch (StatusRuntimeException e) {
            log.error("Reserve RPC failed: accountId={}, amount={}, requestId={}",
                    accountId, amount, requestId, e);
            return false;
        }
    }

    /**
     * 예약 해제 (주문 취소 시)
     * @return true if success, false if error
     */
    public boolean unreserve(long accountId, BigDecimal amount) {
        String requestId = UUID.randomUUID().toString();

        UnreserveRequest request = UnreserveRequest.newBuilder()
                .setAccountId(accountId)
                .setAmount(amount.toPlainString())
                .setRequestId(requestId)
                .build();

        try {
            UnreserveReply reply = blockingStub.unreserve(request);

            if (reply.getCode() == ResultCode.SUCCESS) {
                log.debug("Unreserve success: accountId={}, amount={}, requestId={}",
                        accountId, amount, requestId);
                return true;
            } else {
                log.warn("Unreserve failed: accountId={}, amount={}, code={}, requestId={}",
                        accountId, amount, reply.getCode(), requestId);
                return false;
            }
        } catch (StatusRuntimeException e) {
            log.error("Unreserve RPC failed: accountId={}, amount={}, requestId={}",
                    accountId, amount, requestId, e);
            return false;
        }
    }

    /**
     * Shutdown the channel gracefully
     */
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("AccountGrpcClient shutdown complete");
        } catch (InterruptedException e) {
            log.error("AccountGrpcClient shutdown interrupted", e);
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
