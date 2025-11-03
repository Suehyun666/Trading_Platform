CREATE SEQUENCE IF NOT EXISTS account_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE accounts (
                          account_id BIGINT UNIQUE NOT NULL,
                          account_no VARCHAR(32) NOT NULL,
                          balance NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
                          reserved NUMERIC(18,4) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
                          currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                          status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_accounts_account_id ON accounts(account_id);
CREATE INDEX idx_accounts_currency ON accounts(currency);
CREATE INDEX idx_accounts_status ON accounts(status);

CREATE TABLE positions (
                           id BIGSERIAL PRIMARY KEY,
                           account_id BIGINT NOT NULL,
                           symbol VARCHAR(32) NOT NULL,
                           quantity NUMERIC(18,4) NOT NULL DEFAULT 0
                               CHECK (quantity >= 0),
                           avg_price NUMERIC(18,4) NOT NULL DEFAULT 0
                               CHECK (avg_price >= 0),
                           realized_pnl NUMERIC(18,4) NOT NULL DEFAULT 0,
                           updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_positions_account_symbol ON positions(account_id, symbol);

CREATE OR REPLACE FUNCTION prevent_negative_positions()
RETURNS trigger AS $$
BEGIN
    IF NEW.quantity < 0 THEN
        RAISE EXCEPTION 'Negative quantity not allowed for account_id %, symbol %', NEW.account_id, NEW.symbol;
END IF;
    IF NEW.avg_price < 0 THEN
        RAISE EXCEPTION 'Negative avg_price not allowed for account_id %, symbol %', NEW.account_id, NEW.symbol;
END IF;
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_prevent_negative_positions
    BEFORE INSERT OR UPDATE ON positions
                         FOR EACH ROW
                         EXECUTE FUNCTION prevent_negative_positions();
