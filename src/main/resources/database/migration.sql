-- Syncmoney BigDecimal Precision Migration Script
-- Purpose: To migrate the balance field from DOUBLE/FLOAT to DECIMAL(20, 2)
-- Date: 2026-02-21
-- Version: 1.0.0

-- ============================================================
-- Migration Description:
-- This script is used to migrate the existing balance data from floating-point types to precise decimal types
-- to ensure financial calculations are not affected by floating-point precision issues
-- ============================================================

-- Step 1: Check the current balance field type
-- Note: Please adjust the actual table name (this script assumes the table name is `players`)

-- MySQL Migration Script (MySQL 5.7+)
-- ============================================================

-- Check if the table exists
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
AND table_name = 'players';

-- Step 2: Add a new field (if the original field is DOUBLE/FLOAT)
ALTER TABLE players
ADD COLUMN balance_new DECIMAL(20, 2) DEFAULT 0;

-- Step 3: Migrate data (ensure precision)
UPDATE players
SET balance_new = CAST(balance AS DECIMAL(20, 2));

-- Step 4: Verify migrated data (check if any data is lost)
SELECT
    player_uuid,
    balance AS old_balance,
    balance_new AS new_balance,
    (balance - balance_new) AS diff
FROM players
WHERE ABS(balance - balance_new) > 0.01;

-- Step 5: Delete the old field
ALTER TABLE players
DROP COLUMN balance;

-- Step 6: Rename the new field
ALTER TABLE players
RENAME COLUMN balance_new TO balance;

-- Step 7: Add index (optional, improve query performance)
CREATE INDEX idx_players_balance ON players(balance);

-- ============================================================
-- PostgreSQL Migration Script (backup)
-- ============================================================

-- PostgreSQL use ALTER COLUMN type conversion
-- ALTER TABLE players
-- ALTER COLUMN balance TYPE DECIMAL(20, 2)
-- USING balance::DECIMAL(20, 2);

-- ============================================================
-- Verify migration results
-- ============================================================

-- Check the new field type
SELECT
    column_name,
    data_type,
    numeric_precision,
    numeric_scale
FROM information_schema.columns
WHERE table_name = 'players'
AND column_name = 'balance';

-- Verify the total balance consistency (should be consistent before and after migration)
SELECT
    SUM(balance) AS total_balance,
    COUNT(*) AS player_count
FROM players;

-- ============================================================
-- Audit log table
-- ============================================================

-- Add sequence column if not exists (for existing tables)
ALTER TABLE syncmoney_audit_log
ADD COLUMN IF NOT EXISTS sequence INT DEFAULT 0;

-- Add composite index for timestamp + sequence sorting
CREATE INDEX IF NOT EXISTS idx_audit_timestamp_sequence
ON syncmoney_audit_log(timestamp DESC, sequence DESC);

-- Add player_uuid index if not exists
CREATE INDEX IF NOT EXISTS idx_audit_player_uuid
ON syncmoney_audit_log(player_uuid);

-- Create audit log table (with sequence column)
CREATE TABLE IF NOT EXISTS syncmoney_audit_log (
    id VARCHAR(36) PRIMARY KEY,
    timestamp BIGINT NOT NULL,
    sequence INT DEFAULT 0,
    type VARCHAR(20) NOT NULL,
    player_uuid VARCHAR(36) NOT NULL,
    player_name VARCHAR(16),
    amount DECIMAL(20, 2) NOT NULL,
    balance_after DECIMAL(20, 2) NOT NULL,
    source VARCHAR(30) NOT NULL,
    server VARCHAR(50) NOT NULL,
    target_uuid VARCHAR(36),
    target_name VARCHAR(16),
    reason VARCHAR(255),
    merged_count INT DEFAULT 1,
    INDEX idx_player_uuid (player_uuid),
    INDEX idx_timestamp (timestamp),
    INDEX idx_type (type),
    INDEX idx_audit_timestamp_sequence (timestamp DESC, sequence DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
