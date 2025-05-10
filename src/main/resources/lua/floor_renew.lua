-- Extend the current grant, never beyond grantedAt + maxBurst.
-- KEYS[1] = floor string, KEYS[2] = active-floors zset
-- ARGV[1] = sessionId, ARGV[2] = token, ARGV[3] = now ms, ARGV[4] = ttl ms,
-- ARGV[5] = max burst ms, ARGV[6] = grace ms
-- Returns {"RENEWED", newValue} | {"MAX_BURST", currentValue} | {"NOT_HOLDER"}
local sid, token = ARGV[1], ARGV[2]
local now, ttl, maxBurst, grace = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6])

local cur = redis.call('GET', KEYS[1])
if not cur then
  return {'NOT_HOLDER'}
end
local h = {}
for s in string.gmatch(cur, '[^|]+') do h[#h + 1] = s end
if h[4] ~= token or tonumber(h[6]) <= now then
  return {'NOT_HOLDER'}
end

local cap = tonumber(h[5]) + maxBurst
local newExpiry = math.min(now + ttl, cap)
if newExpiry <= tonumber(h[6]) then
  return {'MAX_BURST', cur}
end
local value = h[1] .. '|' .. h[2] .. '|' .. h[3] .. '|' .. h[4] .. '|' .. h[5] .. '|' .. tostring(newExpiry)
redis.call('SET', KEYS[1], value, 'PX', newExpiry - now + grace)
redis.call('ZADD', KEYS[2], newExpiry, sid)
return {'RENEWED', value}
