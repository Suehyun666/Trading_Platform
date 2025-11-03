package com.hts.account.domain.service;

import com.hts.account.domain.model.ServiceResult;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;

public interface AccountService {

    /**
     * 예수금 예약 (balance → reserved)
     */
    @Transactional
    ServiceResult reserve(Long accountId, BigDecimal amount, String requestId);

    /**
     * 예약 취소 (reserved → balance)
     */
    @Transactional
    ServiceResult unreserve(Long accountId, BigDecimal amount, String requestId);

    /**
     * 체결 반영 (reserved → 소멸)
     */
    @Transactional
    ServiceResult applyFill(Long accountId, BigDecimal filledAmount, String symbol, BigDecimal price, BigDecimal qty, String side, String requestId);

    /**
     * 단순 잔액 조회
     */
    //BigDecimal getBalance(Long accountId);

    /**
     * 단순 예약금 조회
     */
    //BigDecimal getReserved(Long accountId);
}
