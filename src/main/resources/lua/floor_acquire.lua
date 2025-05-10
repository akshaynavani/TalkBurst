-- Request the floor for a session.
-- KEYS[1] = floor string, KEYS[2] = queue zset, KEYS[3] = active-floors zset
-- ARGV[1] = sessionId, ARGV[2] = member "userId|ROLE|prio", ARGV[3] = canPreempt (0/1),
-- ARGV[4] = token for a new grant, ARGV[5] = now ms, ARGV[6] = grant ttl ms,
-- ARGV[7] = grace ms, ARGV[8] = max queue length, ARGV[9] = queue score
--
-- Floor value: "userId|ROLE|prio|token|grantedAtMs|expiresAtMs"
-- Returns one of:
--   {"GRANTED", newValue, expiredPrev}
--   {"HELD", currentValue}
--   {"PREEMPTED", newValue, revokedValue}
--   {"QUEUED", rank, expiredPrev, grantedToOther}
--   {"QUEUE_FULL"}
local sid, member, canPreempt = ARGV[1], ARGV[2], tonumber(ARGV[3])
local token, now, ttl, grace = ARGV[4], tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7])
local maxQueue, score = tonumber(ARGV[8]), tonumber(ARGV[9])

local function parse(v)
  local t = {}
  for s in string.gmatch(v, '[^|]+') do t[#t + 1] = s end
  return t
end

local function grant(m)
  local expires = now + ttl
  local value = m .. '|' .. token .. '|' .. tostring(now) .. '|' .. tostring(expires)
  redis.call('SET', KEYS[1], value, 'PX', ttl + grace)
  redis.call('ZADD', KEYS[3], expires, sid)
  return value
end

local function promote()
  local head = redis.call('ZPOPMIN', KEYS[2])
  if #head == 0 then
    redis.call('ZREM', KEYS[3], sid)
    return ''
  end
  return grant(head[1])
end

local me = parse(member)
local expiredPrev = ''
local cur = redis.call('GET', KEYS[1])
local holder
if cur then
  holder = parse(cur)
  if tonumber(holder[6]) <= now then
    expiredPrev = cur
    redis.call('DEL', KEYS[1])
    cur = nil
  end
end

if cur then
  if holder[1] == me[1] then
    return {'HELD', cur}
  end
  if canPreempt == 1 and tonumber(me[3]) < tonumber(holder[3]) then
    redis.call('ZREM', KEYS[2], member)
    return {'PREEMPTED', grant(member), cur}
  end
  if not redis.call('ZSCORE', KEYS[2], member) then
    if redis.call('ZCARD', KEYS[2]) >= maxQueue then
      return {'QUEUE_FULL'}
    end
    redis.call('ZADD', KEYS[2], score, member)
  end
  return {'QUEUED', tostring(redis.call('ZRANK', KEYS[2], member)), expiredPrev, ''}
end

-- Floor is free. Join the queue and let the best-ranked waiter take it, so a
-- higher-priority request already waiting is never jumped.
redis.call('ZADD', KEYS[2], 'NX', score, member)
local granted = promote()
if parse(granted)[1] == me[1] then
  return {'GRANTED', granted, expiredPrev}
end
return {'QUEUED', tostring(redis.call('ZRANK', KEYS[2], member)), expiredPrev, granted}
