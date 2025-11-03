-- V2__IdempotencyTables.sql
-- 멱등성 보장 및 감사 추적을 위한 테이블

-- 예약/해제/체결 요청 이력 (멱등성 보장)
CREATE TABLE request_history (
    request_id VARCHAR(64) PRIMARY KEY,
    request_type VARCHAR(32) NOT NULL,  -- RESERVE, UNRESERVE, APPLY_FILL
    account_id BIGINT NOT NULL,
    amount NUMERIC(18,4),
    symbol VARCHAR(32),
    fill_price NUMERIC(18,4),
    quantity NUMERIC(18,4),
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',  -- PROCESSING, SUCCESS, FAILED
    result_code VARCHAR(32),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP
);

CREATE INDEX idx_request_history_account ON request_history(account_id);
CREATE INDEX idx_request_history_created ON request_history(created_at);
CREATE INDEX idx_request_history_status ON request_history(status);

-- 계좌 예약 상세 이력 (주문별 예약 추적)
CREATE TABLE account_reserves (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    order_id VARCHAR(64),  -- Order 서비스의 주문 ID (nullable for direct reserves)
    request_id VARCHAR(64) NOT NULL,  -- 멱등성 키
    amount NUMERIC(18,4) NOT NULL,
    status VARCHAR(16) NOT NULL,  -- RESERVED, RELEASED, APPLIED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP,
    applied_at TIMESTAMP
);

CREATE INDEX idx_reserves_account ON account_reserves(account_id);
CREATE INDEX idx_reserves_order ON account_reserves(order_id);
CREATE INDEX idx_reserves_request ON account_reserves(request_id);
CREATE INDEX idx_reserves_status ON account_reserves(status);

-- Transactional Outbox Pattern (Phase 3에서 활성화)
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,  -- account_id 등
    payload JSONB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'  -- PENDING, PUBLISHED, FAILED
);

CREATE INDEX idx_outbox_status ON outbox(status);
CREATE INDEX idx_outbox_created ON outbox(created_at);
