-- Atomic set balance with globally monotonic version.
-- KEYS[1] = version key (syncmoney:version:{uuid})
-- KEYS[2] = balance key (syncmoney:balance:{uuid})
-- KEYS[3] = operation receipt key (syncmoney:operation:receipt:{sha256(operationId)})
-- ARGV[1] = new balance
-- ARGV[2] = canonical operation payload fingerprint
-- ARGV[3] = optional operation receipt TTL seconds; defaults to 0 (no expiry)

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

local function parseCents(value)
    if value == nil or #value == 0 or #value > 64 then return nil, 'INVALID_AMOUNT' end
    local sign, integerPart, fractionPart = string.match(value, '^([%+%-]?)(%d+)%.?(%d*)$')
    if not integerPart then return nil, 'INVALID_AMOUNT' end
    if sign == '-' and string.find(integerPart .. fractionPart, '[1-9]') then
        return nil, 'NEGATIVE_BALANCE'
    end

    local cents = trimLeadingZeros(integerPart .. string.sub(fractionPart .. '00', 1, 2))
    if string.sub(fractionPart, 3, 3) >= '5' then
        cents = addUnsigned(cents, '1')
    end
    if compareUnsigned(cents, MAX_CENTS) > 0 then return nil, 'INVALID_AMOUNT' end
    return cents, nil
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

local keyVersion = KEYS[1]
local keyBalance = KEYS[2]
local keyReceipt = KEYS[3]
if not keyReceipt or not ARGV[2] or #ARGV[2] ~= 64 or not string.match(ARGV[2], '^[0-9a-f]+$') then
    return redis.error_reply('INVALID_OPERATION_RECEIPT')
end
local receiptTtlRaw = ARGV[3] or '0'
if not string.match(receiptTtlRaw, '^%d+$') or #receiptTtlRaw > 6 then
    return redis.error_reply('INVALID_OPERATION_RECEIPT')
end
local receiptTtlSeconds = tonumber(receiptTtlRaw)
if receiptTtlSeconds > 604800 then return redis.error_reply('INVALID_OPERATION_RECEIPT') end
local function storeReceipt(raw)
    if receiptTtlSeconds == 0 then
        redis.call('SET', keyReceipt, raw)
    else
        redis.call('SET', keyReceipt, raw, 'EX', receiptTtlSeconds)
    end
end
if not keyHasSupportedType(keyReceipt) then return redis.error_reply('INVALID_OPERATION_RECEIPT') end
local priorRaw = redis.call('GET', keyReceipt)
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

local function reject(reason)
    local raw = encodeReceipt('REJECTED', reason, {})
    storeReceipt(raw)
    return raw
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

local keyVersion = KEYS[1]
local keyBalance = KEYS[2]
local newBalanceCents, balanceError = parseCents(ARGV[1])

if not newBalanceCents then
    return reject(balanceError)
end
if not keyHasSupportedType(keyVersion) or not keyHasSupportedType(keyBalance) then
    return reject('INVALID_KEY_TYPE')
end

local versionCanAdvance, versionError = canIncrementVersion(keyVersion)
if not versionCanAdvance then return reject(versionError) end

-- Exact decimal-string parsing preserves every cent through DECIMAL(20,2)'s full range.
local resultBalance = formatCents(newBalanceCents)
storeReceipt(encodeReceipt('IN_FLIGHT', '-', {}))
redis.call('INCR', keyVersion)
redis.call('SET', keyBalance, resultBalance)
local newVersion = redis.call('GET', keyVersion)
local applied = encodeReceipt('APPLIED', '-', {resultBalance, newVersion})
storeReceipt(applied)
return applied
