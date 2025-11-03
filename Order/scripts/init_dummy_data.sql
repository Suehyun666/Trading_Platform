-- Create dummy accounts (1000-2000) with initial balance
-- Run: psql -U trading_user -d trading -f scripts/init_dummy_data.sql

-- Insert accounts with $100,000 initial balance (10,000,000 cents)
INSERT INTO accounts (account_id, balance, created_at, updated_at)
SELECT
    generate_series AS account_id,
    10000000 AS balance,
    NOW() AS created_at,
    NOW() AS updated_at
FROM generate_series(1000, 2000);

-- Verify
SELECT COUNT(*) as total_accounts,
       MIN(account_id) as min_id,
       MAX(account_id) as max_id,
       SUM(balance) as total_balance
FROM accounts;
