-- Atomic set balance with globally monotonic version
-- INCR version key to get new version, then set balance.
-- KEYS[1] = version key (syncmoney:version:{uuid})
-- KEYS[2] = balance key (syncmoney:balance:{uuid})
-- ARGV[1] = new balance (number)

local keyVersion = KEYS[1]
local keyBalance = KEYS[2]
local newBalance = tonumber(ARGV[1])

if newBalance < 0 then
    return redis.error_reply('NEGATIVE_BALANCE')
end

local newVersion = redis.call('INCR', keyVersion)
redis.call('SET', keyBalance, string.format('%.2f', newBalance))
return tostring(newVersion)
