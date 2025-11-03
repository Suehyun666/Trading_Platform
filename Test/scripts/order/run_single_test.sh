#!/bin/bash
# Run single order test for account 1000

cd "$(dirname "$0")"

echo "Starting single order test..."
./gradlew run --console=plain --quiet -PmainClass=com.hts.test.SingleOrderTest
