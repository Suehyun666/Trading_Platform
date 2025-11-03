package com.hts.account.application;

import com.hts.account.domain.model.ServiceResult;
import com.hts.account.domain.service.AccountService;
import com.hts.account.domain.service.PositionService;
import com.hts.account.infrastructure.event.TransactionalEventPublisher;
import com.hts.account.infrastructure.repository.AccountJOOQRepository;
import com.hts.account.infrastructure.repository.OperationStatus;
import com.hts.generated.grpc.ResultCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

/**
 * 계좌 애플리케이션 서비스
 *
 * ShardWorker에서만 호출됨 (절대 gRPC에서 직접 호출 금지)
 * 여기서만 @Transactional 실행
 */
@ApplicationScoped
public class AccountServiceImpl implements AccountService {

    private static final Logger log = Logger.getLogger(AccountServiceImpl.class);

    @Inject AccountJOOQRepository repo;
    @Inject TransactionalEventPublisher eventPublisher;
    @Inject
    PositionService positionService;

    /**
     * 예약 (Reserve) - 초고속 버전
     * SELECT 제거: 모든 검증을 Repository의 SQL로 위임
     */
    @Transactional
    @Override
    public ServiceResult reserve(Long accountId, BigDecimal amount, String requestId) {
        try {
            if (amount.compareTo(BigDecimal.ZERO) <= 0)
                return ServiceResult.of(ResultCode.INVALID_REQUEST);

            OperationStatus status = repo.tryReserve(accountId, amount, requestId);
            ServiceResult result = ServiceResult.from(status);

            if (result.isSuccess())
                eventPublisher.publishAfterCommit("ACCOUNT_RESERVED", accountId, amount);

            return result;
        } catch (Exception e) {
            log.errorf(e, "Reserve failed (accountId=%d, req=%s)", accountId, requestId);
            return ServiceResult.of(ResultCode.INTERNAL_ERROR);
        }
    }

    @Transactional
    @Override
    public ServiceResult unreserve(Long accountId, BigDecimal amount, String requestId) {
        try {
            if (amount.compareTo(BigDecimal.ZERO) <= 0)
                return ServiceResult.of(ResultCode.INVALID_REQUEST);

            OperationStatus status = repo.tryUnreserve(accountId, amount, requestId);
            ServiceResult result = ServiceResult.from(status);

            if (result.isSuccess())
                eventPublisher.publishAfterCommit("ACCOUNT_UNRESERVED", accountId, amount);

            return result;
        } catch (Exception e) {
            log.error("Unreserve failed", e);
            return ServiceResult.of(ResultCode.INTERNAL_ERROR);
        }
    }

    /**
     * 체결 반영 (ApplyFill) - 초고속 버전
     */
    @Transactional
    @Override
    public ServiceResult applyFill(Long accountId, BigDecimal filledAmount,
                                   String symbol, BigDecimal price,
                                   BigDecimal qty, String side, String requestId) {
        try {
            boolean ok = repo.tryApplyFill(accountId, filledAmount, requestId);
            if (!ok)
                return ServiceResult.of(ResultCode.INSUFFICIENT_FUNDS);

            if ("BUY".equals(side))
                positionService.addOrUpdate(accountId, symbol, qty, price);
            else if ("SELL".equals(side))
                positionService.reduce(accountId, symbol, qty, price);

            eventPublisher.publishAfterCommit("ACCOUNT_FILL_APPLIED", accountId, filledAmount);
            return ServiceResult.of(ResultCode.SUCCESS);
        } catch (Exception e) {
            log.error("ApplyFill failed", e);
            return ServiceResult.of(ResultCode.INTERNAL_ERROR);
        }
    }
}
