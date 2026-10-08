-- Atomic set balance with globally monotonic version.
-- KEYS[1] = version key (syncmoney:version:{uuid})
-- KEYS[2] = balance key (syncmoney:balance:{uuid})
-- ARGV[1] = new balance

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
    return redis.error_reply(balanceError)
end
if not keyHasSupportedType(keyVersion) or not keyHasSupportedType(keyBalance) then
    return redis.error_reply('INVALID_KEY_TYPE')
end

local versionCanAdvance, versionError = canIncrementVersion(keyVersion)
if not versionCanAdvance then return redis.error_reply(versionError) end

-- Exact decimal-string parsing preserves every cent through DECIMAL(20,2)'s full range.
local resultBalance = formatCents(newBalanceCents)
redis.call('INCR', keyVersion)
redis.call('SET', keyBalance, resultBalance)
return redis.call('GET', keyVersion)
