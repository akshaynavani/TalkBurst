-- Expire a lapsed grant (if any) and promote the next waiter.
-- Safe to run concurrently from several nodes: only one sees the lapsed grant.
-- KEYS[1] = floor string, KEYS[2] = queue zset, KEYS[3] = active-floors zset
-- ARGV[1] = sessionId, ARGV[2] = token for the promoted grant, ARGV[3] = now ms,
-- ARGV[4] = ttl ms, ARGV[5] = grace ms
-- Returns {"ACTIVE"} | {"IDLE"} | {"EXPIRED", expiredValue, promotedValue}
local sid, token = ARGV[1], ARGV[2]
local now, ttl, grace = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])

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

local cur = redis.call('GET', KEYS[1])
if cur then
  local h = {}
  for s in string.gmatch(cur, '[^|]+') do h[#h + 1] = s end
  if tonumber(h[6]) > now then
    redis.call('ZADD', KEYS[3], tonumber(h[6]), sid)
    return {'ACTIVE'}
  end
  redis.call('DEL', KEYS[1])
end

local promoted = promote()
if not cur and promoted == '' then
  return {'IDLE'}
end
return {'EXPIRED', cur or '', promoted}
