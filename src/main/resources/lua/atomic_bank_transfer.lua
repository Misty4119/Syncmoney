-- atomic_bank_transfer.lua
-- Atomic bank-to-bank transfer script (compatible with Redis 7.x)
-- KEYS[1]: source bank balance key
-- KEYS[2]: destination bank balance key
-- KEYS[3]: source bank version key
-- KEYS[4]: destination bank version key
-- ARGV[1]: transfer amount

-- Helper function: truncate to 2 decimal places without rounding
local function truncateToTwoDecimals(num)
    return math.floor(num * 100 + 0.0000001) / 100
end

local sourceBalanceKey = KEYS[1]
local destBalanceKey = KEYS[2]
local sourceVersionKey = KEYS[3]
local destVersionKey = KEYS[4]
local amount = tonumber(ARGV[1])

if not amount or amount <= 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Get current balances
local sourceBalance = tonumber(redis.call('GET', sourceBalanceKey) or '0')
local destBalance = tonumber(redis.call('GET', destBalanceKey) or '0')

-- Check if source bank has sufficient funds
if sourceBalance < amount then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Increment version numbers
local newSourceVersion = redis.call('INCR', sourceVersionKey)
local newDestVersion = redis.call('INCR', destVersionKey)

-- Calculate new balances
local newSourceBalance = sourceBalance - amount
local newDestBalance = destBalance + amount

-- Set new balances atomically
redis.call('SET', sourceBalanceKey, string.format('%.2f', truncateToTwoDecimals(newSourceBalance)))
redis.call('SET', destBalanceKey, string.format('%.2f', truncateToTwoDecimals(newDestBalance)))

-- Return the result
return {
    string.format('%.2f', truncateToTwoDecimals(newSourceBalance)),
    string.format('%.2f', truncateToTwoDecimals(newDestBalance)),
    tostring(newSourceVersion),
    tostring(newDestVersion)
}
