-- Atomic admission for one unit. Redis runs the script on its command thread,
-- so no other client can interleave between the stock read and the decrement.
--
-- All keys share the {sale:<id>} hash tag so they land in one cluster slot.
--
-- KEYS[1] = {sale:<id>}:stock
-- KEYS[2] = {sale:<id>}:buyers
-- KEYS[3] = {sale:<id>}:hold:<userId>
-- KEYS[4] = {sale:<id>}:idem:<userId>:<key>
-- ARGV[1] = userId
-- ARGV[2] = reservationId
-- ARGV[3] = holdTtlSeconds
--
-- Returns {code, reservationId}:
--   1  accepted
--   0  replay (same idempotency key)
--  -1  sold out
--  -2  duplicate (buyer already holds or bought)

local prior = redis.call('GET', KEYS[4])
if prior then
  return {0, prior}
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return {-2, ''}
end
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
  return {-1, ''}
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[3])
redis.call('SET', KEYS[4], ARGV[2], 'EX', 86400)
return {1, ARGV[2]}
