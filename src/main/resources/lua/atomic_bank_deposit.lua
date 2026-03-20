-- atomic_bank_deposit.lua
-- Atomic bank deposit script (compatible with Redis 7.x)
-- KEYS[1]: bank balance key
-- KEYS[2]: bank version key
-- ARGV[1]: deposit amount

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

-- Increment the version number
local newVersion = redis.call('INCR', bankVersionKey)

-- Calculate new balance
local newBalance = currentBalance + amount

-- Set the new balance atomically
redis.call('SET', bankBalanceKey, string.format('%.2f', truncateToTwoDecimals(newBalance)))

-- Return the result
return {
    string.format('%.2f', truncateToTwoDecimals(newBalance)),
    string.format('%.2f', truncateToTwoDecimals(amount)),
    tostring(newVersion)
}
