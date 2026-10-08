-- atomic_transfer.lua
-- KEYS[1] = sender balance key
-- KEYS[2] = sender version key
-- KEYS[3] = receiver balance key
-- KEYS[4] = receiver version key
-- ARGV[1] = transfer amount

local MAX_CENTS = '99999999999999999999'
local MAX_VERSION = '9223372036854775807'

local function trimLeadingZeros(value)
    local trimmed = string.gsub(value, '^0+', '')
    return trimmed == '' and '0' or trimmed
end

local function compareUnsigned(left, right)
    left = trimLeadingZeros(left)
    right = trimLeadingZeros(right)
    if #left < #right then return -1 end
    if #left > #right then return 1 end
    if left < right then return -1 end
    if left > right then return 1 end
    return 0
end

local function addUnsigned(left, right)
    local i = #left
    local j = #right
    local carry = 0
    local digits = {}
    while i > 0 or j > 0 or carry > 0 do
        local a = i > 0 and tonumber(string.sub(left, i, i)) or 0
        local b = j > 0 and tonumber(string.sub(right, j, j)) or 0
        local sum = a + b + carry
        table.insert(digits, 1, tostring(sum % 10))
        carry = math.floor(sum / 10)
        i = i - 1
        j = j - 1
    end
    return table.concat(digits)
end

local function subtractUnsigned(left, right)
    local i = #left
    local j = #right
    local borrow = 0
    local digits = {}
    while i > 0 do
        local a = tonumber(string.sub(left, i, i)) - borrow
        local b = j > 0 and tonumber(string.sub(right, j, j)) or 0
        if a < b then
            a = a + 10
            borrow = 1
        else
            borrow = 0
        end
        table.insert(digits, 1, tostring(a - b))
        i = i - 1
        j = j - 1
    end
    return trimLeadingZeros(table.concat(digits))
end

local function parseCents(value)
    if value == nil or #value == 0 or #value > 64 then return nil end
    local sign, integerPart, fractionPart = string.match(value, '^([%+%-]?)(%d+)%.?(%d*)$')
    if not integerPart or sign == '-' then return nil end

    local cents = trimLeadingZeros(integerPart .. string.sub(fractionPart .. '00', 1, 2))
    if string.sub(fractionPart, 3, 3) >= '5' then
        cents = addUnsigned(cents, '1')
    end
    if compareUnsigned(cents, MAX_CENTS) > 0 then return nil end
    return cents
end

local function formatCents(cents)
    while #cents < 3 do cents = '0' .. cents end
    return string.sub(cents, 1, #cents - 2) .. '.' .. string.sub(cents, #cents - 1)
end

local function keyHasSupportedType(key)
    local keyType = redis.call('TYPE', key)
    local typeName = keyType.ok or keyType
    return typeName == 'none' or typeName == 'string'
end

local function canIncrementVersion(key)
    local rawVersion = redis.call('GET', key)
    if not rawVersion then
        return true, nil
    end
    if not string.match(rawVersion, '^%d+$')
            or (#rawVersion > 1 and string.sub(rawVersion, 1, 1) == '0') then
        return false, 'INVALID_VERSION'
    end
    if #rawVersion > #MAX_VERSION
            or (#rawVersion == #MAX_VERSION and rawVersion >= MAX_VERSION) then
        return false, 'VERSION_OVERFLOW'
    end
    return true, nil
end

local senderBalanceKey = KEYS[1]
local senderVersionKey = KEYS[2]
local receiverBalanceKey = KEYS[3]
local receiverVersionKey = KEYS[4]

if senderBalanceKey == receiverBalanceKey or senderVersionKey == receiverVersionKey then
    return redis.error_reply('SELF_TRANSFER')
end
if not keyHasSupportedType(senderBalanceKey)
        or not keyHasSupportedType(senderVersionKey)
        or not keyHasSupportedType(receiverBalanceKey)
        or not keyHasSupportedType(receiverVersionKey) then
    return redis.error_reply('INVALID_KEY_TYPE')
end

local amountCents = parseCents(ARGV[1])
if not amountCents or amountCents == '0' then
    return redis.error_reply('INVALID_AMOUNT')
end
local senderCents = parseCents(redis.call('GET', senderBalanceKey) or '0')
local receiverCents = parseCents(redis.call('GET', receiverBalanceKey) or '0')
if not senderCents or not receiverCents then
    return redis.error_reply('INVALID_BALANCE')
end
if compareUnsigned(senderCents, amountCents) < 0 then
    return redis.error_reply('INSUFFICIENT_FUNDS')
end

local senderNewCents = subtractUnsigned(senderCents, amountCents)
local receiverNewCents = addUnsigned(receiverCents, amountCents)
if compareUnsigned(receiverNewCents, MAX_CENTS) > 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

local senderCanAdvance, senderVersionError = canIncrementVersion(senderVersionKey)
if not senderCanAdvance then return redis.error_reply(senderVersionError) end
local receiverCanAdvance, receiverVersionError = canIncrementVersion(receiverVersionKey)
if not receiverCanAdvance then return redis.error_reply(receiverVersionError) end

-- Exact decimal-string arithmetic validates both account ranges before either balance changes.
local senderResult = formatCents(senderNewCents)
local receiverResult = formatCents(receiverNewCents)
redis.call('SET', senderBalanceKey, senderResult)
redis.call('SET', receiverBalanceKey, receiverResult)
redis.call('INCR', senderVersionKey)
redis.call('INCR', receiverVersionKey)
local senderNewVersion = redis.call('GET', senderVersionKey)
local receiverNewVersion = redis.call('GET', receiverVersionKey)

return {senderResult, receiverResult, senderNewVersion, receiverNewVersion}
