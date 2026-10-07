-- Return one unit for this reservation at most once.
-- The hold key may already have expired (Redis TTL); the reconciler must
-- still return the unit. A second call, or a call for a reservation that
-- no longer owns the hold (the buyer has a newer one), is a no-op.
--
-- KEYS[1] = {sale:<id>}:stock
-- KEYS[2] = {sale:<id>}:buyers
-- KEYS[3] = {sale:<id>}:hold:<userId>
-- KEYS[4] = {sale:<id>}:idem:<userId>:<key>
-- KEYS[5] = {sale:<id>}:released:<reservationId>
-- ARGV[1] = userId
-- ARGV[2] = reservationId
--
-- Returns 1 if a unit was returned, 0 if there was nothing to release.

if redis.call('EXISTS', KEYS[5]) == 1 then
  return 0
end
local hold = redis.call('GET', KEYS[3])
if hold and hold ~= ARGV[2] then
  redis.call('SET', KEYS[5], '1', 'EX', 86400)
  return 0
end
redis.call('SET', KEYS[5], '1', 'EX', 86400)
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
if hold then
  redis.call('DEL', KEYS[3])
end
if KEYS[4] and KEYS[4] ~= '' then
  redis.call('DEL', KEYS[4])
end
return 1
