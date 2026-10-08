-- Token-bucket counters with a one-second window.
-- KEYS[1] = {rl}:user:<userId>
-- KEYS[2] = {rl}:global
-- ARGV[1] = user limit
-- ARGV[2] = global limit
-- ARGV[3] = window seconds
--
-- Returns 0 allowed, 1 user limited, 2 global limited.

local user = redis.call('INCR', KEYS[1])
if user == 1 then
  redis.call('EXPIRE', KEYS[1], ARGV[3])
end
if user > tonumber(ARGV[1]) then
  return 1
end
local glob = redis.call('INCR', KEYS[2])
if glob == 1 then
  redis.call('EXPIRE', KEYS[2], ARGV[3])
end
if glob > tonumber(ARGV[2]) then
  return 2
end
return 0
