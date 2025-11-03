#!/bin/bash
# Monitor PostgreSQL during load test to observe lock contention

echo "========================================="
echo "PostgreSQL CONTENTION MONITORING"
echo "========================================="
echo ""
echo "Run this script DURING the load test in a separate terminal"
echo "Press Ctrl+C to stop"
echo ""

export PGPASSWORD=trading_password

while true; do
    clear
    echo "========================================="
    echo "PostgreSQL Live Monitoring - $(date '+%H:%M:%S')"
    echo "========================================="
    echo ""

    echo "--- Active Connections ---"
    psql -U trading_user -d trading -h localhost -c "
        SELECT count(*) as active_connections, state
        FROM pg_stat_activity
        WHERE datname = 'trading'
        GROUP BY state;
    " 2>/dev/null

    echo ""
    echo "--- Lock Waits (blocked queries) ---"
    psql -U trading_user -d trading -h localhost -c "
        SELECT
            COUNT(*) as blocked_queries,
            locktype,
            mode
        FROM pg_locks
        WHERE NOT granted
        GROUP BY locktype, mode;
    " 2>/dev/null

    echo ""
    echo "--- Active Locks by Type ---"
    psql -U trading_user -d trading -h localhost -c "
        SELECT
            locktype,
            mode,
            COUNT(*) as lock_count
        FROM pg_locks
        WHERE granted
        GROUP BY locktype, mode
        ORDER BY lock_count DESC
        LIMIT 10;
    " 2>/dev/null

    echo ""
    echo "--- Transaction Stats ---"
    psql -U trading_user -d trading -h localhost -c "
        SELECT
            xact_commit as commits,
            xact_rollback as rollbacks,
            ROUND(xact_commit::numeric / NULLIF(xact_commit + xact_rollback, 0) * 100, 2) as commit_ratio_pct
        FROM pg_stat_database
        WHERE datname = 'trading';
    " 2>/dev/null

    echo ""
    echo "--- Top 5 Slowest Queries (if any) ---"
    psql -U trading_user -d trading -h localhost -c "
        SELECT
            pid,
            ROUND(EXTRACT(epoch FROM (now() - query_start))::numeric, 2) as duration_sec,
            LEFT(query, 60) as query_snippet
        FROM pg_stat_activity
        WHERE datname = 'trading'
          AND state = 'active'
          AND query NOT LIKE '%pg_stat_activity%'
        ORDER BY query_start
        LIMIT 5;
    " 2>/dev/null

    echo ""
    echo "Refreshing in 2 seconds... (Ctrl+C to stop)"
    sleep 2
done
