package com.hts.account.domain.service;

import com.hts.account.domain.model.PositionRecord;

import java.math.BigDecimal;
import java.util.Optional;

public interface PositionService {

    /**
     * 포지션 추가 or 평균단가 갱신 (매수 체결)
     */
    void addOrUpdate(Long accountId, String symbol, BigDecimal quantity, BigDecimal price);

    /**
     * 포지션 감소 or 청산 (매도 체결)
     */
    void reduce(Long accountId, String symbol, BigDecimal quantity, BigDecimal price);

    /**
     * 포지션 조회
     */
    Optional<PositionRecord> findByAccountAndSymbol(Long accountId, String symbol);
}
