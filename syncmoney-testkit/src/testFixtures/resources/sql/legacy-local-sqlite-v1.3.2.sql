-- DDL is copied from LocalEconomyHandler at 1.3.2 source commit 71cc55d; rows below are synthetic.
CREATE TABLE player_balances (
    uuid TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    balance REAL NOT NULL DEFAULT 0.0,
    version INTEGER NOT NULL DEFAULT 0,
    last_update INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE TABLE transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uuid TEXT NOT NULL,
    name TEXT,
    type TEXT NOT NULL,
    amount REAL NOT NULL,
    balance_before REAL NOT NULL,
    balance_after REAL NOT NULL,
    source TEXT NOT NULL,
    target_name TEXT,
    timestamp INTEGER NOT NULL,
    sequence INTEGER DEFAULT 0
);
CREATE INDEX idx_transactions_timestamp ON transactions(timestamp DESC);
CREATE INDEX idx_transactions_timestamp_seq ON transactions(timestamp DESC, sequence DESC);
CREATE INDEX idx_balance ON player_balances(balance DESC);
CREATE INDEX idx_last_update ON player_balances(last_update);
CREATE INDEX idx_transactions_uuid ON transactions(uuid);
INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'fixture-a', 100.0, 7, 1700000000000, 1600000000000);
INSERT INTO player_balances (uuid, name, balance, version, last_update, created_at)
VALUES ('00000000-0000-0000-0000-000000000002', 'fixture-b', 0.0, 3, 1700000000001, 1600000000001);
INSERT INTO transactions (uuid, name, type, amount, balance_before, balance_after, source, target_name, timestamp, sequence)
VALUES ('00000000-0000-0000-0000-000000000001', 'fixture-a', 'DEPOSIT', 100.0, 0.0, 100.0, 'TEST', NULL, 1700000000000, 1);
