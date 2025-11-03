#!/bin/bash
# Run concurrent order test for 1000 clients

cd "$(dirname "$0")"

echo "Starting concurrent order test (1000 clients)..."
./gradlew run --console=plain --quiet -PmainClass=com.hts.test.ContinuousLoadTest
