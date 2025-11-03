package com.hts.account.infrastructure.repository;

import com.hts.account.domain.model.PositionRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@ApplicationScoped
public class PositionJOOQRepository {

    private static final Logger log = Logger.getLogger(PositionJOOQRepository.class);

    @Inject
    DSLContext dsl;

    /**
     * 포지션 조회 (계좌 ID + 종목)
     */
    public PositionRecord findByAccountAndSymbol(long accountId, String symbol) {
        return dsl.fetchOne("""
            SELECT id, account_id, symbol, quantity, avg_price, realized_pnl, updated_at
              FROM positions
             WHERE account_id = ? AND symbol = ?
        """, accountId, symbol).into(PositionRecord.class);
    }

    /**
     * 계좌의 모든 포지션 조회
     */
    public List<PositionRecord> findByAccount(long accountId) {
        return dsl.fetch("""
            SELECT id, account_id, symbol, quantity, avg_price, realized_pnl, updated_at
              FROM positions
             WHERE account_id = ?
             ORDER BY symbol
        """, accountId).into(PositionRecord.class);
    }

    /**
     * 포지션 업데이트 (매수 체결 시)
     * - 수량 증가
     * - 평균 단가 재계산
     */
    public void upsertPositionBuy(long accountId, String symbol, BigDecimal quantity, BigDecimal fillPrice) {
        try {
            int rows = dsl.execute("""
                INSERT INTO positions (account_id, symbol, quantity, avg_price, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (account_id, symbol)
                DO UPDATE SET
                    quantity = positions.quantity + EXCLUDED.quantity,
                    avg_price = (positions.quantity * positions.avg_price + EXCLUDED.quantity * EXCLUDED.avg_price)
                                / (positions.quantity + EXCLUDED.quantity),
                    updated_at = now()
            """, accountId, symbol, quantity, fillPrice);

            log.infof("Position updated (BUY): accountId=%d, symbol=%s, qty=%s, price=%s, rows=%d",
                     accountId, symbol, quantity, fillPrice, rows);
        } catch (DataAccessException e) {
            log.errorf(e, "Failed to upsert position (BUY): accountId=%d, symbol=%s", accountId, symbol);
            throw e;
        }
    }

    /**
     * 포지션 업데이트 (매도 체결 시)
     * - 수량 감소
     * - 실현손익 계산
     */
    public void updatePositionSell(long accountId, String symbol, BigDecimal quantity, BigDecimal fillPrice) {
        try {
            // 1. 기존 포지션 조회
            PositionRecord existing = findByAccountAndSymbol(accountId, symbol);
            if (existing == null) {
                throw new IllegalStateException("No position found for SELL: accountId=" + accountId + ", symbol=" + symbol);
            }

            // 2. 실현손익 계산: (매도가 - 평균단가) * 수량
            BigDecimal realizedPnl = fillPrice.subtract(existing.avgPrice())
                                              .multiply(quantity)
                                              .setScale(4, RoundingMode.HALF_UP);

            // 3. 수량 차감 및 실현손익 누적
            int rows = dsl.execute("""
                UPDATE positions
                   SET quantity = quantity - ?,
                       realized_pnl = realized_pnl + ?,
                       updated_at = now()
                 WHERE account_id = ? AND symbol = ? AND quantity >= ?
            """, quantity, realizedPnl, accountId, symbol, quantity);

            if (rows == 0) {
                throw new IllegalStateException("Insufficient position quantity: accountId=" + accountId
                                                + ", symbol=" + symbol + ", required=" + quantity);
            }

            log.infof("Position updated (SELL): accountId=%d, symbol=%s, qty=%s, price=%s, pnl=%s",
                     accountId, symbol, quantity, fillPrice, realizedPnl);
        } catch (DataAccessException e) {
            log.errorf(e, "Failed to update position (SELL): accountId=%d, symbol=%s", accountId, symbol);
            throw e;
        }
    }

    /**
     * 포지션 삭제 (수량이 0인 경우)
     */
    public void deleteZeroPositions(long accountId) {
        int rows = dsl.execute("""
            DELETE FROM positions
             WHERE account_id = ? AND quantity = 0
        """, accountId);

        if (rows > 0) {
            log.infof("Deleted %d zero positions for accountId=%d", rows, accountId);
        }
    }
}
