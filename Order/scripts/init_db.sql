-- Run this script as postgres superuser
-- Step 1: psql -U postgres -f scripts/init_db.sql
-- Step 2: psql -U postgres -d trading -f scripts/init_db_grants.sql

CREATE DATABASE trading;

CREATE USER trading_user WITH PASSWORD 'trading_password';

GRANT ALL PRIVILEGES ON DATABASE trading TO trading_user;
