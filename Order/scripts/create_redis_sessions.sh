#!/bin/bash
# Create Redis sessions for accounts 1000-2000
# Session ID = Account ID for simplicity

echo "Creating Redis sessions for accounts 1000-2000..."

for i in {1..6000}; do
  redis-cli SET "session:$i" "$i" EX 86400 > /dev/null
done

echo "Done! Created 1001 sessions."
echo "Verify: redis-cli GET session:1000"
