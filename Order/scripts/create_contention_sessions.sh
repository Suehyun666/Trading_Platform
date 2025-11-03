#!/bin/bash
# Create Redis sessions for contention testing
# Multiple sessions will share the same account to test concurrent access

echo "Creating contention test sessions..."
echo "Strategy: 1000 sessions → 50 accounts (20 sessions per account)"

# Session IDs: 20000-20999 (1000 sessions)
# Account IDs: 1000-1049 (50 accounts)
# Each account has 20 sessions

for i in {0..999}; do
  session_id=$((20000 + i))
  account_id=$((1000 + i / 20))  # Integer division: 20 sessions per account

  redis-cli SET "session:$session_id" "$account_id" EX 86400 > /dev/null

  if [ $((i % 100)) -eq 0 ]; then
    echo "Progress: $i/1000 sessions created..."
  fi
done

echo ""
echo "Done! Created 1000 sessions for contention testing."
echo ""
echo "Session distribution:"
echo "  - Session IDs: 20000-20999 (1000 sessions)"
echo "  - Account IDs: 1000-1049 (50 accounts)"
echo "  - Sessions per account: 20"
echo ""
echo "Verify examples:"
echo "  redis-cli GET session:20000  # Should return: 1000"
echo "  redis-cli GET session:20019  # Should return: 1000 (same account)"
echo "  redis-cli GET session:20020  # Should return: 1001 (next account)"
echo ""

# Verification
echo "Verification:"
redis-cli GET session:20000
redis-cli GET session:20019
redis-cli GET session:20020
