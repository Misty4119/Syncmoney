-- atomic_audit.lua
-- Redis List audit log atomic operation script
-- Implements sliding window for recent audit records
-- KEYS[1]: audit list key (syncmoney:audit:recent)
-- KEYS[2]: audit index key (syncmoney:audit:index)
-- ARGV[1]: max size (sliding window size)
-- ARGV[2]: record JSON

local listKey = KEYS[1]
local indexKey = KEYS[2]
local maxSize = tonumber(ARGV[1])
local recordJson = ARGV[2]

-- Validate inputs
if not maxSize or maxSize <= 0 then
    return redis.error_reply('INVALID_MAX_SIZE')
end

if not recordJson or #recordJson == 0 then
    return redis.error_reply('INVALID_RECORD')
end

-- Add to list head (most recent first)
redis.call('LPUSH', listKey, recordJson)

-- Sliding window: trim if exceeds max size
local currentSize = redis.call('LLEN', listKey)
if currentSize > maxSize then
    redis.call('LTRIM', listKey, 0, maxSize - 1)
end

-- Increment index (for tracking migration progress)
local newIndex = redis.call('INCR', indexKey)

-- Return current size and new index
return {currentSize, newIndex}
