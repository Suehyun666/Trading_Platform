#!/bin/bash
# Real-time transaction rate monitor
# Usage: ./monitor_tx_rate.sh [interval_seconds]

INTERVAL=${1:-1}

echo "Monitoring PostgreSQL transaction rate (interval: ${INTERVAL}s)"
echo "Press Ctrl+C to stop"
echo ""
printf "%20s %12s %12s %12s %12s %12s\n" "Time" "Commits" "Commits/s" "Inserts" "Updates" "TPS"
echo "--------------------------------------------------------------------------------"

# Get initial values
PREV_STATS=$(psql -h localhost -U trading_user -d trading -W -tAc "SELECT xact_commit, tup_inserted, tup_updated FROM pg_stat_database WHERE datname = 'trading';" <<< "trading_password" 2>/dev/null)
PREV_COMMIT=$(echo "$PREV_STATS" | cut -d'|' -f1)
PREV_INSERT=$(echo "$PREV_STATS" | cut -d'|' -f2)
PREV_UPDATE=$(echo "$PREV_STATS" | cut -d'|' -f3)
PREV_TIME=$(date +%s)

while true; do
    sleep "$INTERVAL"

    CURR_STATS=$(psql -h localhost -U trading_user -d trading -W -tAc "SELECT xact_commit, tup_inserted, tup_updated FROM pg_stat_database WHERE datname = 'trading';" <<< "trading_password" 2>/dev/null)
    CURR_COMMIT=$(echo "$CURR_STATS" | cut -d'|' -f1)
    CURR_INSERT=$(echo "$CURR_STATS" | cut -d'|' -f2)
    CURR_UPDATE=$(echo "$CURR_STATS" | cut -d'|' -f3)
    CURR_TIME=$(date +%s)

    # Calculate deltas
    DELTA_COMMIT=$((CURR_COMMIT - PREV_COMMIT))
    DELTA_INSERT=$((CURR_INSERT - PREV_INSERT))
    DELTA_UPDATE=$((CURR_UPDATE - PREV_UPDATE))
    DELTA_TIME=$((CURR_TIME - PREV_TIME))

    # Calculate rates
    if [ "$DELTA_TIME" -gt 0 ]; then
        COMMIT_RATE=$((DELTA_COMMIT / DELTA_TIME))
        TPS=$((COMMIT_RATE))
    else
        COMMIT_RATE=0
        TPS=0
    fi

    # Display
    TIMESTAMP=$(date +"%Y-%m-%d %H:%M:%S")
    printf "%20s %12d %12d %12d %12d %12d\n" \
        "$TIMESTAMP" "$CURR_COMMIT" "$COMMIT_RATE" "$DELTA_INSERT" "$DELTA_UPDATE" "$TPS"

    # Update previous values
    PREV_COMMIT=$CURR_COMMIT
    PREV_INSERT=$CURR_INSERT
    PREV_UPDATE=$CURR_UPDATE
    PREV_TIME=$CURR_TIME
done
