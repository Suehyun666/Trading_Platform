#!/bin/bash
# Run contention order test
# This test creates 1000 clients sharing only 50 accounts (20 sessions per account)

cd "$(dirname "$0")"

echo "========================================="
echo "CONTENTION LOAD TEST"
echo "========================================="
echo ""
echo "This test will:"
echo "  - Create 1000 concurrent clients"
echo "  - Map them to only 50 accounts (20 sessions per account)"
echo "  - Send requests WITHOUT throttling (maximum burst)"
echo "  - Test row-level lock contention in PostgreSQL"
echo ""
echo "Expected behavior:"
echo "  - Lower throughput than baseline test"
echo "  - Higher latency (P95, P99)"
echo "  - Possible 'Insufficient balance' errors due to concurrent access"
echo ""
echo "Starting test..."
echo ""

./gradlew run --console=plain --quiet -PmainClass=com.hts.test.ContentionLoadTest
