-- atomic_add_balance.lua
-- KEYS[1] = balance key (syncmoney:balance:{uuid})
-- KEYS[2] = version key (syncmoney:version:{uuid})
-- ARGV[1] = amount to add (positive = deposit, negative = withdraw)

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

local function parseCents(value, allowNegative, invalidCode)
    if value == nil or #value == 0 or #value > 64 then
        return nil, nil, invalidCode
    end
    local sign, integerPart, fractionPart = string.match(value, '^([%+%-]?)(%d+)%.?(%d*)$')
    if not integerPart or (sign == '-' and not allowNegative) then
        return nil, nil, invalidCode
    end

    local cents = trimLeadingZeros(integerPart .. string.sub(fractionPart .. '00', 1, 2))
    if string.sub(fractionPart, 3, 3) >= '5' then
        cents = addUnsigned(cents, '1')
    end
    if compareUnsigned(cents, MAX_CENTS) > 0 then
        return nil, nil, invalidCode
    end
    if sign == '-' and cents ~= '0' then
        return cents, -1, nil
    end
    return cents, 1, nil
end

local function addSigned(left, leftSign, right, rightSign)
    if leftSign == rightSign then
        return addUnsigned(left, right), leftSign
    end
    local comparison = compareUnsigned(left, right)
    if comparison == 0 then return '0', 1 end
    if comparison > 0 then return subtractUnsigned(left, right), leftSign end
    return subtractUnsigned(right, left), rightSign
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

local keyBalance = KEYS[1]
local keyVersion = KEYS[2]

if not keyHasSupportedType(keyBalance) or not keyHasSupportedType(keyVersion) then
    return redis.error_reply('INVALID_KEY_TYPE')
end

local currentCents, currentSign, balanceError = parseCents(
        redis.call('GET', keyBalance) or '0', false, 'INVALID_BALANCE')
if balanceError then return redis.error_reply(balanceError) end

local amountCents, amountSign, amountError = parseCents(ARGV[1], true, 'INVALID_AMOUNT')
if amountError then return redis.error_reply(amountError) end

local newCents, newSign = addSigned(currentCents, currentSign, amountCents, amountSign)
if newSign < 0 then return redis.error_reply('INSUFFICIENT_FUNDS') end
if compareUnsigned(newCents, MAX_CENTS) > 0 then
    return redis.error_reply('INVALID_AMOUNT')
end

local versionCanAdvance, versionError = canIncrementVersion(keyVersion)
if not versionCanAdvance then return redis.error_reply(versionError) end

-- Exact decimal-string arithmetic keeps cents and the SQL DECIMAL(20,2) range intact.
local resultBalance = formatCents(newCents)
redis.call('SET', keyBalance, resultBalance)
redis.call('INCR', keyVersion)
local newVersion = redis.call('GET', keyVersion)
return {resultBalance, newVersion}
