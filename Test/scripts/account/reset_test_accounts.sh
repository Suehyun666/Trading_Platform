#!/bin/bash

echo "========================================="
echo "Account Test DB Reset Script"
echo "========================================="

DB_USER="hts"
DB_NAME="hts_account"
export PGPASSWORD="hts"

echo ""
echo "[1/4] Resetting balance and reserved amounts for test accounts (1000-2000)..."
psql -U $DB_USER $DB_NAME <<EOF
UPDATE accounts
SET balance = 1000000.00,
    reserved = 0.00,
    updated_at = now()
WHERE account_id BETWEEN 1000 AND 2000;
EOF

if [ $? -eq 0 ]; then
    echo " Account balances reset successfully"
else
    echo " Failed to reset account balances"
    exit 1
fi

echo ""
echo "[2/4] Clearing account_reserves table for test accounts..."
psql -U $DB_USER $DB_NAME <<EOF
DELETE FROM account_reserves
WHERE account_id BETWEEN 1000 AND 2000;
EOF

if [ $? -eq 0 ]; then
    echo " Account reserves cleared successfully"
else
    echo " Failed to clear account reserves"
    exit 1
fi

echo ""
echo "[3/4] Clearing request_history for test accounts..."
psql -U $DB_USER $DB_NAME <<EOF
DELETE FROM request_history
WHERE account_id BETWEEN 1000 AND 2000;
EOF

if [ $? -eq 0 ]; then
    echo " Request history cleared successfully"
else
    echo " Failed to clear request history"
    exit 1
fi

echo ""
echo "[4/4] Verifying reset..."
psql -U $DB_USER $DB_NAME <<EOF
SELECT
    COUNT(*) as total_accounts,
    SUM(CASE WHEN balance = 1000000.00 THEN 1 ELSE 0 END) as correct_balance,
    SUM(CASE WHEN reserved = 0.00 THEN 1 ELSE 0 END) as zero_reserved
FROM accounts
WHERE account_id BETWEEN 1000 AND 2000;
EOF

echo ""
echo "========================================="
echo "Reset completed successfully!"
echo "========================================="
echo ""
echo "Test accounts (1000-2000) are now ready:"
echo "  - Balance: 1,000,000.00 KRW"
echo "  - Reserved: 0.00 KRW"
echo "  - No pending reserves"
echo "  - No request history"
echo ""
