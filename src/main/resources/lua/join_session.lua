-- Atomically add a member to a session, enforcing capacity.
-- KEYS[1] = session meta hash, KEYS[2] = members hash
-- ARGV[1] = userId, ARGV[2] = encoded member, ARGV[3] = max participants, ARGV[4] = idle ttl ms
-- Returns: 1 joined, 0 rejoined (already a member), -1 no such session, -2 session full
if redis.call('EXISTS', KEYS[1]) == 0 then
  return -1
end
local existed = redis.call('HEXISTS', KEYS[2], ARGV[1])
if existed == 0 and redis.call('HLEN', KEYS[2]) >= tonumber(ARGV[3]) then
  return -2
end
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
redis.call('PEXPIRE', KEYS[2], ARGV[4])
if existed == 1 then
  return 0
end
return 1
