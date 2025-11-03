package com.hts.test.account;

import com.hts.generated.grpc.ReserveReply;
import com.hts.generated.grpc.UnreserveReply;
import com.hts.test.account.client.GrpcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

public final class ClientPool {
    private final List<GrpcClient> clients;
    private final Random rnd = new Random();

    public ClientPool(List<GrpcClient> clients) {
        this.clients = clients;
    }

    private GrpcClient pick() {
        return clients.get(rnd.nextInt(clients.size()));
    }

    public ReserveReply reserve(Long accountId, BigDecimal amount, String requestId) {
        return pick().reserve(accountId, amount, requestId);
    }

    public UnreserveReply unreserve(Long accountId, BigDecimal amount, String requestId) {
        return pick().unreserve(accountId, amount, requestId);
    }
}