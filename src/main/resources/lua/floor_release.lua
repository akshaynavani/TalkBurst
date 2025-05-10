-- Release the floor and hand it to the next queued requester.
-- KEYS[1] = floor string, KEYS[2] = queue zset, KEYS[3] = active-floors zset
-- ARGV[1] = sessionId, ARGV[2] = match mode ("token" | "user"), ARGV[3] = value to match,
-- ARGV[4] = token for the promoted grant, ARGV[5] = now ms, ARGV[6] = ttl ms, ARGV[7] = grace ms
-- Returns {"RELEASED", releasedValue, promotedValue} or {"NOT_HOLDER"}
local sid, mode, match = ARGV[1], ARGV[2], ARGV[3]
local token, now, ttl, grace = ARGV[4], tonumber(ARGV[5]), tonumber(ARGV[6]), tonumber(ARGV[7])

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

local cur = redis.call('GET', KEYS[1])
if not cur then
  return {'NOT_HOLDER'}
end
local h = parse(cur)
local matched = (mode == 'token' and h[4] == match) or (mode == 'user' and h[1] == match)
if not matched then
  return {'NOT_HOLDER'}
end
redis.call('DEL', KEYS[1])
return {'RELEASED', cur, promote()}
