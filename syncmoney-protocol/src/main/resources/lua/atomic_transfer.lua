-- atomic_transfer.lua
-- KEYS[1] = sender balance key
-- KEYS[2] = sender version key
-- KEYS[3] = receiver balance key
-- KEYS[4] = receiver version key
-- KEYS[5] = operation receipt key (syncmoney:operation:receipt:{sha256(operationId)})
-- ARGV[1] = transfer amount
-- ARGV[2] = canonical operation payload fingerprint
-- ARGV[3] = optional receipt TTL seconds; 0 (default) keeps stable receipts durable

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

local function splitReceipt(raw)
    local fields = {}
    for field in string.gmatch(raw .. '|', '(.-)|') do
        table.insert(fields, field)
    end
    return fields
end

local function encodeReceipt(status, reason, accountValues)
    local fields = {'v1', ARGV[2], status, reason or '-', tostring(#accountValues / 2)}
    for _, value in ipairs(accountValues) do table.insert(fields, value) end
    return table.concat(fields, '|')
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
local receiptKey = KEYS[5]
if not receiptKey or not ARGV[2] or #ARGV[2] ~= 64 or not string.match(ARGV[2], '^[0-9a-f]+$') then
    return redis.error_reply('INVALID_OPERATION_RECEIPT')
end
local receiptTtlRaw = ARGV[3] or '0'
if not string.match(receiptTtlRaw, '^%d+$') or #receiptTtlRaw > 6 then
    return redis.error_reply('INVALID_OPERATION_RECEIPT')
end
local receiptTtlSeconds = tonumber(receiptTtlRaw)
if receiptTtlSeconds > 604800 then return redis.error_reply('INVALID_OPERATION_RECEIPT') end
if not keyHasSupportedType(receiptKey) then return redis.error_reply('INVALID_OPERATION_RECEIPT') end

local function storeReceipt(raw)
    if receiptTtlSeconds == 0 then
        redis.call('SET', receiptKey, raw)
    else
        redis.call('SET', receiptKey, raw, 'EX', receiptTtlSeconds)
    end
    return raw
end

local priorRaw = redis.call('GET', receiptKey)
if priorRaw then
    local fields = splitReceipt(priorRaw)
    if #fields < 5 or fields[1] ~= 'v1' or not string.match(fields[2], '^[0-9a-f]+$')
            or #fields[2] ~= 64 or (fields[3] ~= 'APPLIED' and fields[3] ~= 'REJECTED'
            and fields[3] ~= 'IN_FLIGHT') then
        return redis.error_reply('INVALID_OPERATION_RECEIPT')
    end
    if fields[2] ~= ARGV[2] then return redis.error_reply('OPERATION_ID_COLLISION') end
    if fields[3] == 'IN_FLIGHT' then return redis.error_reply('OPERATION_IN_FLIGHT') end
    return priorRaw
end

local function reject(reason, accountValues)
    local raw = encodeReceipt('REJECTED', reason, accountValues or {})
    storeReceipt(raw)
    return raw
end

if senderBalanceKey == receiverBalanceKey or senderVersionKey == receiverVersionKey then
    return reject('SELF_TRANSFER')
end
if not keyHasSupportedType(senderBalanceKey)
        or not keyHasSupportedType(senderVersionKey)
        or not keyHasSupportedType(receiverBalanceKey)
        or not keyHasSupportedType(receiverVersionKey) then
    return reject('INVALID_KEY_TYPE')
end

local amountCents = parseCents(ARGV[1])
if not amountCents or amountCents == '0' then
    return reject('INVALID_AMOUNT')
end
local senderBalanceRaw = redis.call('GET', senderBalanceKey)
local senderVersionRaw = redis.call('GET', senderVersionKey)
local receiverBalanceRaw = redis.call('GET', receiverBalanceKey)
local receiverVersionRaw = redis.call('GET', receiverVersionKey)
if (not senderBalanceRaw and senderVersionRaw) or (not receiverBalanceRaw and receiverVersionRaw) then
    return reject('INVALID_ACCOUNT_STATE')
end
local senderCents = parseCents(senderBalanceRaw or '0')
local receiverCents = parseCents(receiverBalanceRaw or '0')
if not senderCents or not receiverCents then
    return reject('INVALID_BALANCE')
end
if compareUnsigned(senderCents, amountCents) < 0 then
    local accounts = senderBalanceRaw and {senderBalanceRaw, senderVersionRaw or '0'} or {'~', '-1'}
    if receiverBalanceRaw then
        table.insert(accounts, receiverBalanceRaw)
        table.insert(accounts, receiverVersionRaw or '0')
    else
        table.insert(accounts, '~')
        table.insert(accounts, '-1')
    end
    return reject('INSUFFICIENT_FUNDS', accounts)
end

local senderNewCents = subtractUnsigned(senderCents, amountCents)
local receiverNewCents = addUnsigned(receiverCents, amountCents)
if compareUnsigned(receiverNewCents, MAX_CENTS) > 0 then
    return reject('INVALID_AMOUNT')
end

local senderCanAdvance, senderVersionError = canIncrementVersion(senderVersionKey)
if not senderCanAdvance then return reject(senderVersionError) end
local receiverCanAdvance, receiverVersionError = canIncrementVersion(receiverVersionKey)
if not receiverCanAdvance then return reject(receiverVersionError) end

-- Exact decimal-string arithmetic validates both account ranges before either balance changes.
local senderResult = formatCents(senderNewCents)
local receiverResult = formatCents(receiverNewCents)
storeReceipt(encodeReceipt('IN_FLIGHT', '-', {}))
redis.call('SET', senderBalanceKey, senderResult)
redis.call('SET', receiverBalanceKey, receiverResult)
redis.call('INCR', senderVersionKey)
redis.call('INCR', receiverVersionKey)
local senderNewVersion = redis.call('GET', senderVersionKey)
local receiverNewVersion = redis.call('GET', receiverVersionKey)

local applied = encodeReceipt('APPLIED', '-', {
    senderResult, senderNewVersion, receiverResult, receiverNewVersion
})
storeReceipt(applied)
return applied
