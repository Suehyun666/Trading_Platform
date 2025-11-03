package com.hts.account.api.grpc;

import com.hts.account.application.AccountServiceImpl;
import com.hts.account.domain.model.ServiceResult;
import com.hts.account.utils.MoneyParser;
import com.hts.generated.grpc.*;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * gRPC Service - Account
 */
@GrpcService
public class AccountGrpcService implements AccountService {

    private static final Logger log = Logger.getLogger(AccountGrpcService.class);

    @Inject
    AccountServiceImpl service;
    @Inject AccountShardInvoker shardInvoker;

    @Override
    public Uni<ReserveReply> reserve(ReserveRequest req) {
        long accountId = req.getAccountId();
        BigDecimal amount = MoneyParser.parse(req.getAmount());
        String requestId = req.getRequestId();

        return Uni.createFrom().completionStage(() ->
                shardInvoker.submit(accountId,
                        () -> service.reserve(accountId, amount, requestId))
        ).onFailure().recoverWithItem(ex -> {
            log.error("reserve failed", ex);
            return ServiceResult.of(ResultCode.INTERNAL_ERROR);
        }).map(this::toReserveReply);
    }

    @Override
    public Uni<UnreserveReply> unreserve(UnreserveRequest req) {
        long accountId = req.getAccountId();
        BigDecimal amount = MoneyParser.parse(req.getAmount());
        String requestId = req.getRequestId();

        return Uni.createFrom().completionStage(() ->
                shardInvoker.submit(accountId,
                        () -> service.unreserve(accountId, amount, requestId))
        ).onFailure().recoverWithItem(ex -> {
            log.error("unreserve failed", ex);
            return ServiceResult.of(ResultCode.INTERNAL_ERROR);
        }).map(this::toUnreserveReply);
    }

    private ReserveReply toReserveReply(ServiceResult result) {
        return ReserveReply.newBuilder()
                .setCode(result.code())
                .build();
    }

    private UnreserveReply toUnreserveReply(ServiceResult result) {
        return UnreserveReply.newBuilder()
                .setCode(result.code())
                .build();
    }
}
