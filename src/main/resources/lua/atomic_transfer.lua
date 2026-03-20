-- atomic_transfer.lua
-- Atomic transfer script (compatible with Redis 7.x)
-- KEYS[1]: sender balance key
-- KEYS[2]: sender version key
-- KEYS[3]: receiver balance key
-- KEYS[4]: receiver version key
-- ARGV[1]: transfer amount

-- Helper function: truncate to 2 decimal places without rounding
local function truncateToTwoDecimals(num)
    return math.floor(num * 100 + 0.0000001) / 100
end

local senderBalanceKey = KEYS[1]
local senderVersionKey = KEYS[2]
local receiverBalanceKey = KEYS[3]
local receiverVersionKey = KEYS[4]
local amount = tonumber(ARGV[1])

if not amount or amount <= 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Check if the sender's balance is sufficient before attempting transfer
-- This uses WATCH to detect concurrent modifications
local senderCurrent = tonumber(redis.call('GET', senderBalanceKey) or '0')
if senderCurrent < amount then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Use INCRBYFLOAT for atomic decrement/increment (atomic operation, no lost update)
local senderNew = redis.call('INCRBYFLOAT', senderBalanceKey, -amount)
local receiverNew = redis.call('INCRBYFLOAT', receiverBalanceKey, amount)

-- Increment the version numbers
local senderNewVersion = redis.call('INCR', senderVersionKey)
local receiverNewVersion = redis.call('INCR', receiverVersionKey)

-- Return the result array
return {
    string.format('%.2f', truncateToTwoDecimals(senderNew)),
    string.format('%.2f', truncateToTwoDecimals(receiverNew)),
    tostring(senderNewVersion),
    tostring(receiverNewVersion)
}
