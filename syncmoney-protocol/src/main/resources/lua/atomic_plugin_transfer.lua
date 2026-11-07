-- atomic_plugin_transfer.lua
-- Plugin-specific atomic transfer script (distinct from regular PLAYER_TRANSFER)
-- KEYS[1]: sender balance key
-- KEYS[2]: sender version key
-- KEYS[3]: receiver balance key
-- KEYS[4]: receiver version key
-- ARGV[1]: transfer amount
-- ARGV[2]: plugin name (for audit logging metadata)

local function truncateToTwoDecimals(num)
    return math.floor(num * 100 + 0.0000001) / 100
end

local senderBalanceKey = KEYS[1]
local senderVersionKey = KEYS[2]
local receiverBalanceKey = KEYS[3]
local receiverVersionKey = KEYS[4]
local amount = tonumber(ARGV[1])
local pluginName = ARGV[2] or "Unknown"

if not amount or amount <= 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

-- Check sender balance
local senderCurrent = tonumber(redis.call('GET', senderBalanceKey) or '0')
if senderCurrent < amount then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

-- Atomic increment/decrement with version update
local senderNew = redis.call('INCRBYFLOAT', senderBalanceKey, -amount)
local receiverNew = redis.call('INCRBYFLOAT', receiverBalanceKey, amount)

local senderNewVersion = redis.call('INCR', senderVersionKey)
local receiverNewVersion = redis.call('INCR', receiverVersionKey)

-- Store plugin metadata for audit (optional, non-blocking)
-- Uses pcall to ensure failures don't affect the main transaction
local auditKey = 'syncmoney:plugin_transfer:' .. senderVersionKey .. ':' .. tostring(redis.call('TIME')[1])
pcall(function()
    redis.call('HSET', auditKey, 'plugin', pluginName, 'amount', amount, 'timestamp', redis.call('TIME')[1])
    redis.call('EXPIRE', auditKey, 86400)  -- 24h TTL
end)

return {
    string.format('%.2f', truncateToTwoDecimals(senderNew)),
    string.format('%.2f', truncateToTwoDecimals(receiverNew)),
    tostring(senderNewVersion),
    tostring(receiverNewVersion)
}
