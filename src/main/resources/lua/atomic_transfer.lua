-- atomic_transfer.lua
-- Atomic transfer script (compatible with Redis 7.x)
-- KEYS[1]: sender balance key
-- KEYS[2]: sender version key
-- KEYS[3]: receiver balance key
-- KEYS[4]: receiver version key
-- ARGV[1]: transfer amount

local senderBalanceKey = KEYS[1]
local senderVersionKey = KEYS[2]
local receiverBalanceKey = KEYS[3]
local receiverVersionKey = KEYS[4]
local amount = tonumber(ARGV[1])

if not amount or amount <= 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Get the sender's current balance
local senderBalance = tonumber(redis.call('GET', senderBalanceKey) or '0')

-- Check if the sender's balance is sufficient
if senderBalance < amount then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Get the receiver's current balance
local receiverBalance = tonumber(redis.call('GET', receiverBalanceKey) or '0')

-- Increment the version number
local senderNewVersion = redis.call('INCR', senderVersionKey)
local receiverNewVersion = redis.call('INCR', receiverVersionKey)

-- Calculate the new balance
local senderNewBalance = senderBalance - amount
local receiverNewBalance = receiverBalance + amount

-- Set the new balance
redis.call('SET', senderBalanceKey, string.format('%.2f', senderNewBalance))
redis.call('SET', receiverBalanceKey, string.format('%.2f', receiverNewBalance))

-- Return the result array
return {
    string.format('%.2f', senderNewBalance),
    string.format('%.2f', receiverNewBalance),
    tostring(senderNewVersion),
    tostring(receiverNewVersion)
}
