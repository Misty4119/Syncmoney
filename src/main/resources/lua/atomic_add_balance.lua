-- atomic_add_balance.lua
-- Atomic balance increase/decrease script (corrected version - removed WATCH)
-- KEYS[1] = balance key (syncmoney:balance:{uuid})
-- KEYS[2] = version key (syncmoney:version:{uuid})
-- ARGV[1] = amount to add (positive = deposit, negative = withdraw)

local keyBalance = KEYS[1]
local keyVersion = KEYS[2]
local amount = tonumber(ARGV[1])

if not amount then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Get current balance (used for balance insufficient check)
local currentBalance = tonumber(redis.call('GET', keyBalance) or '0')
local newBalance = currentBalance + amount

-- Check if the balance is sufficient (only when withdrawing)
if amount < 0 and newBalance < 0 then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Use INCRBYFLOAT to atomically increase the balance
-- INCRBYFLOAT is itself an atomic operation, no need for WATCH/MULTI/EXEC
local resultBalance = redis.call('INCRBYFLOAT', keyBalance, amount)

-- Increment the version number
local newVersion = redis.call('INCR', keyVersion)

-- Return the new balance and the new version number
return {string.format('%.2f', resultBalance), tostring(newVersion)}
