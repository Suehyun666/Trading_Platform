package com.hts.account.infrastructure.repository;

import com.hts.account.domain.model.AccountRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

import java.math.BigDecimal;

@ApplicationScoped
public class AccountJOOQRepository {

    private static final Logger log = Logger.getLogger(AccountRepository.class);
    @Inject DSLContext dsl;

    /**
     * 멱등성 보장 - 이미 처리된 요청인지 확인
     */
    public boolean isDuplicateRequest(String requestId) {
        Integer count = dsl.fetchOne("""
            SELECT COUNT(*) FROM request_history
             WHERE request_id = ?
        """, requestId).into(Integer.class);
        return count != null && count > 0;
    }

    /**
     * 요청 이력 기록 (멱등성 보장)
     */
    public void recordRequest(String requestId, String requestType, long accountId,
                              BigDecimal amount, String status, String resultCode) {
        dsl.execute("""
            INSERT INTO request_history
                (request_id, request_type, account_id, amount, status, result_code, created_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (request_id) DO NOTHING
        """, requestId, requestType, accountId, amount, status, resultCode);
    }

    /**
     * 요청 처리 완료 업데이트
     */
    public void markRequestProcessed(String requestId, String status, String resultCode) {
        dsl.execute("""
            UPDATE request_history
               SET status = ?, result_code = ?, processed_at = now()
             WHERE request_id = ?
        """, status, resultCode, requestId);
    }

    /**
     * 잔고 차감 + 예약금 증가 (초고속 버전 - 성공 시 SELECT 없음)
     * 실패 시에만 원인 파악을 위해 SELECT
     */
    public OperationStatus tryReserve(long accountId, BigDecimal amount, String requestId) {
        try {
            // ✅ 1. 멱등성 체크 INSERT (실패하면 이미 처리된 요청)
            int inserted = dsl.execute("""
                INSERT INTO request_history
                    (request_id, request_type, account_id, amount, status, result_code, created_at)
                VALUES (?, 'RESERVE', ?, ?, 'PROCESSING', NULL, now())
                ON CONFLICT (request_id) DO NOTHING
            """, requestId, accountId, amount);

            // 이미 처리된 요청
            if (inserted == 0) {
                log.debugf("Duplicate reserve request: %s", requestId);
                return OperationStatus.DUPLICATE;
            }

            // ✅ 2. UPDATE accounts (검증 포함)
            int updated = dsl.execute("""
                UPDATE accounts
                   SET balance = balance - ?,
                       reserved = reserved + ?,
                       updated_at = now()
                 WHERE account_id = ?
                   AND balance >= ?
                   AND status = 'ACTIVE'
            """, amount, amount, accountId, amount);

            // 실패 시 원인 파악 (실패한 경우에만 SELECT)
            if (updated != 1) {
                var acc = findById(accountId);

                // 계좌 없음
                if (acc == null) {
                    markRequestProcessed(requestId, "FAILED", "ACCOUNT_NOT_FOUND");
                    log.warnf("Account not found: accountId=%d, requestId=%s", accountId, requestId);
                    return OperationStatus.NOT_FOUND;
                }

                // 계좌 상태 확인
                if (!"ACTIVE".equals(acc.status())) {
                    markRequestProcessed(requestId, "FAILED", "ACCOUNT_SUSPENDED");
                    log.warnf("Account suspended: accountId=%d, status=%s, requestId=%s",
                        accountId, acc.status(), requestId);
                    return OperationStatus.INVALID_STATE;
                }

                // 잔액 부족
                if (acc.balance().compareTo(amount) < 0) {
                    markRequestProcessed(requestId, "FAILED", "INSUFFICIENT_FUNDS");
                    log.warnf("Insufficient funds: accountId=%d, balance=%s, required=%s, requestId=%s",
                        accountId, acc.balance(), amount, requestId);
                    return OperationStatus.INSUFFICIENT_BALANCE;
                }

                // 기타 오류
                markRequestProcessed(requestId, "FAILED", "UNKNOWN_ERROR");
                log.errorf("Unknown reserve failure: accountId=%d, requestId=%s", accountId, requestId);
                return OperationStatus.INTERNAL_ERROR;
            }

            // ✅ 3. 성공 기록
            dsl.batch(
                dsl.query("""
                    UPDATE request_history
                       SET status = 'SUCCESS', result_code = 'RESERVED', processed_at = now()
                     WHERE request_id = ?
                """, requestId),

                dsl.query("""
                    INSERT INTO account_reserves
                        (account_id, request_id, amount, status, created_at)
                    VALUES (?, ?, ?, 'RESERVED', now())
                    ON CONFLICT DO NOTHING
                """, accountId, requestId, amount)
            ).execute();

            return OperationStatus.UPDATED;

        } catch (DataAccessException e) {
            log.errorf(e, "Reserve failed [accountId=%d, amount=%s, requestId=%s]",
                    accountId, amount, requestId);
            markRequestProcessed(requestId, "FAILED", "INTERNAL_ERROR");
            return OperationStatus.INTERNAL_ERROR;
        }
    }

    public OperationStatus tryUnreserve(long accountId, BigDecimal amount, String requestId) {
        try {
            int inserted = dsl.execute("""
                INSERT INTO request_history
                    (request_id, request_type, account_id, amount, status, result_code, created_at)
                VALUES (?, 'UNRESERVE', ?, ?, 'PROCESSING', NULL, now())
                ON CONFLICT (request_id) DO NOTHING
            """, requestId, accountId, amount);

            if (inserted == 0) {
                log.debugf("Duplicate unreserve request: %s", requestId);
                return OperationStatus.DUPLICATE;
            }

            int updated = dsl.execute("""
                UPDATE accounts
                   SET balance = balance + ?,
                       reserved = reserved - ?,
                       updated_at = now()
                 WHERE account_id = ?
                   AND reserved >= ?
            """, amount, amount, accountId, amount);

            if (updated != 1) {
                var acc = findById(accountId);

                if (acc == null) {
                    markRequestProcessed(requestId, "FAILED", "ACCOUNT_NOT_FOUND");
                    log.warnf("Account not found: accountId=%d, requestId=%s", accountId, requestId);
                    return OperationStatus.NOT_FOUND;
                }

                if (!"ACTIVE".equals(acc.status())) {
                    markRequestProcessed(requestId, "FAILED", "ACCOUNT_SUSPENDED");
                    log.warnf("Account suspended: accountId=%d, status=%s, requestId=%s",
                        accountId, acc.status(), requestId);
                    return OperationStatus.INVALID_STATE;
                }

                if (acc.reserved().compareTo(amount) < 0) {
                    markRequestProcessed(requestId, "FAILED", "INSUFFICIENT_RESERVED");
                    log.warnf("Insufficient reserved: accountId=%d, reserved=%s, required=%s, requestId=%s",
                        accountId, acc.reserved(), amount, requestId);
                    return OperationStatus.INSUFFICIENT_BALANCE;
                }

                markRequestProcessed(requestId, "FAILED", "UNKNOWN_ERROR");
                log.errorf("Unknown unreserve failure: accountId=%d, requestId=%s", accountId, requestId);
                return OperationStatus.INTERNAL_ERROR;
            }

            dsl.batch(
                dsl.query("""
                    UPDATE request_history
                       SET status = 'SUCCESS', result_code = 'UNRESERVED', processed_at = now()
                     WHERE request_id = ?
                """, requestId),

                dsl.query("""
                    UPDATE account_reserves
                       SET status = 'RELEASED', released_at = now()
                     WHERE request_id = ?
                """, requestId)
            ).execute();

            return OperationStatus.UPDATED;

        } catch (DataAccessException e) {
            log.errorf(e, "Unreserve failed [accountId=%d, amount=%s, requestId=%s]",
                    accountId, amount, requestId);
            markRequestProcessed(requestId, "FAILED", "INTERNAL_ERROR");
            return OperationStatus.INTERNAL_ERROR;
        }
    }

    /**
     * 체결 반영 (초고속 버전 - SELECT 완전 제거)
     */
    public boolean tryApplyFill(long accountId, BigDecimal filledAmount, String requestId) {
        try {
            // ✅ 1. 멱등성 체크 INSERT
            int inserted = dsl.execute("""
                INSERT INTO request_history
                    (request_id, request_type, account_id, amount, status, result_code, created_at)
                VALUES (?, 'APPLY_FILL', ?, ?, 'PROCESSING', NULL, now())
                ON CONFLICT (request_id) DO NOTHING
            """, requestId, accountId, filledAmount);

            if (inserted == 0) {
                log.debugf("Duplicate applyFill request: %s", requestId);
                return true;
            }

            // ✅ 2. UPDATE accounts
            int updated = dsl.execute("""
                UPDATE accounts
                   SET reserved = reserved - ?,
                       updated_at = now()
                 WHERE account_id = ?
                   AND reserved >= ?
                   AND status = 'ACTIVE'
            """, filledAmount, accountId, filledAmount);

            if (updated != 1) {
                markRequestProcessed(requestId, "FAILED", "INSUFFICIENT_RESERVED");
                return false;
            }

            // ✅ 3. 성공 기록
            dsl.batch(
                dsl.query("""
                    UPDATE request_history
                       SET status = 'SUCCESS', result_code = 'FILL_APPLIED', processed_at = now()
                     WHERE request_id = ?
                """, requestId),

                dsl.query("""
                    UPDATE account_reserves
                       SET status = 'APPLIED', applied_at = now()
                     WHERE request_id = ?
                """, requestId)
            ).execute();

            return true;

        } catch (DataAccessException e) {
            log.errorf("ApplyFill failed [accountId=%d, amount=%s, requestId=%s]: %s",
                    accountId, filledAmount, requestId, e.getMessage());
            markRequestProcessed(requestId, "FAILED", "INTERNAL_ERROR");
            throw e;
        }
    }


    /**
     * 단일 계좌 조회
     * @return 계좌가 없으면 null 반환
     */
    public AccountRecord findById(long accountId) {
        var record = dsl.fetchOne("""
            SELECT account_id, account_no, balance, reserved, currency, status, updated_at
              FROM accounts
             WHERE account_id = ?
        """, accountId);

        if (record == null) {
            return null;
        }

        return record.into(AccountRecord.class);
    }
}
