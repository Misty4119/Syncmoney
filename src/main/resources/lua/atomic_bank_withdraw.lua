-- atomic_bank_withdraw.lua
-- Atomic bank withdrawal script (compatible with Redis 7.x)
-- KEYS[1]: bank balance key
-- KEYS[2]: bank version key
-- ARGV[1]: withdrawal amount
-- ARGV[2]: new owner balance (optional, for transfer to player)

-- Helper function: truncate to 2 decimal places without rounding
local function truncateToTwoDecimals(num)
    return math.floor(num * 100 + 0.0000001) / 100
end

local bankBalanceKey = KEYS[1]
local bankVersionKey = KEYS[2]
local amount = tonumber(ARGV[1])

if not amount or amount <= 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Get current bank balance
local currentBalance = tonumber(redis.call('GET', bankBalanceKey) or '0')

-- Check if bank has sufficient funds
if currentBalance < amount then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Increment the version number
local newVersion = redis.call('INCR', bankVersionKey)

-- Calculate new balance
local newBalance = currentBalance - amount

-- Set the new balance atomically
redis.call('SET', bankBalanceKey, string.format('%.2f', truncateToTwoDecimals(newBalance)))

-- Return the result
return {
    string.format('%.2f', truncateToTwoDecimals(newBalance)),
    string.format('%.2f', truncateToTwoDecimals(amount)),
    tostring(newVersion)
}
