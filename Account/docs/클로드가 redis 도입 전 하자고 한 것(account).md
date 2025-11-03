다. 현재 성능 분석:

  | Threads | TPS   | p95(ms) | 상태       |
  |---------|-------|---------|----------|
  | 100     | 4.5K  | 30      | ✅ 목표 달성  |
  | 300     | 8K    | 55      | ❌ 5ms 초과 |
  | 500+    | 9-10K | 69-122  | ❌ 병목 명확  |

  핵심 문제: 300 threads부터 p95가 50ms 넘어감

  ---
  🎯 해결책: Stored Procedure 먼저 시도

  왜냐면:

  1. 현재 구조의 문제
    - findById → tryReserve → afterCommit = 3단계 DB 왕복
    - 각 단계마다 network latency + JDBC overhead
  2. Stored Procedure로 줄이면
    - SELECT reserve_funds(?, ?, ?) = 1회 DB 왕복
    - p95를 30-40ms로 줄일 수 있음
  3. Redis는 나중에
    - Stored Procedure로도 안 되면 그때 고려
    - 지금은 Over-engineering

  ---
  ✅ 당장 할 일: Stored Procedure 구현

  1. PostgreSQL 함수 생성

  -- V3__StoredProcedures.sql
  CREATE OR REPLACE FUNCTION reserve_funds(
      p_account_id BIGINT,
      p_amount NUMERIC(19,4),
      p_request_id VARCHAR(255)
  ) RETURNS TABLE(
      success BOOLEAN,
      code VARCHAR(50),
      message TEXT
  ) AS $$
  BEGIN
      -- 멱등성 체크
      IF EXISTS (SELECT 1 FROM request_log WHERE request_id = p_request_id)
   THEN
          RETURN QUERY SELECT false, 'DUPLICATE_REQUEST', '이미 처리된 
  요청입니다';
          RETURN;
      END IF;

      -- 계좌 업데이트 (atomic)
      UPDATE accounts
      SET balance = balance - p_amount,
          reserved = reserved + p_amount,
          updated_at = CURRENT_TIMESTAMP
      WHERE account_id = p_account_id
        AND balance >= p_amount
        AND status = 'ACTIVE';

      IF NOT FOUND THEN
          RETURN QUERY SELECT false, 'INSUFFICIENT_FUNDS', '잔액 부족 또는 
  계좌 없음';
          RETURN;
      END IF;

      -- 요청 로그 기록
      INSERT INTO request_log (request_id, created_at)
      VALUES (p_request_id, CURRENT_TIMESTAMP);

      RETURN QUERY SELECT true, 'SUCCESS', '예약 성공';
  END;
  $$ LANGUAGE plpgsql;

  2. Repository 수정

  // AccountJOOQRepository.java
  public AccountResult reserveWithProcedure(long accountId, BigDecimal 
  amount, String requestId) {
      var result = dsl.fetchOne(
          "SELECT * FROM reserve_funds({0}, {1}, {2})",
          accountId, amount, requestId
      );

      boolean success = result.get("success", Boolean.class);
      String code = result.get("code", String.class);
      String message = result.get("message", String.class);

      return success
          ? AccountResult.success(ResultCode.SUCCESS, message)
          : AccountResult.fail(ResultCode.valueOf(code), message);
  }

  3. Service 수정

  // AccountApplicationService.java
  @Transactional
  @Override
  public AccountResult reserve(Long accountId, BigDecimal amount, String 
  requestId) {
      // 🔥 Stored Procedure 1회 호출로 끝
      AccountResult result = repo.reserveWithProcedure(accountId, amount,
  requestId);

      if (result.success()) {
          eventPublisher.publishAfterCommit("ACCOUNT_RESERVED", accountId,
  amount);
      }

      return result;
  }

  ---
  📊 예상 성능 개선

  | 항목                 | 이전 (3단계) | Stored Procedure (1단계) |
  |--------------------|----------|------------------------|
  | DB 왕복              | 3회       | 1회                     |
  | p95 (300 threads)  | 55ms     | 30-40ms ✅              |
  | p95 (1000 threads) | 122ms    | 50-70ms                |

  ---
  🚀 실행 순서

  1. Migration 파일 생성 (V3__StoredProcedures.sql)
  2. Repository에 reserveWithProcedure() 추가
  3. Service 수정
  4. 테스트

  이거 먼저 해라. Stored Procedure로 안 되면 그때 Redis 고려한다.

  원하면 전체 코드(reserve/unreserve/applyFill 모두 Stored Procedure 버전)
  작성해줄까?


