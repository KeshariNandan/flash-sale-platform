-- Return one unit if, and only if, this reservation still owns the hold.
-- Idempotent: a second call, or a call after TTL expiry, is a no-op so the
-- reconciler and a late payment-failure cannot double-increment stock.
--
-- KEYS[1] = {sale:<id>}:stock
-- KEYS[2] = {sale:<id>}:buyers
-- KEYS[3] = {sale:<id>}:hold:<userId>
-- KEYS[4] = {sale:<id>}:idem:<userId>:<key>
-- ARGV[1] = userId
-- ARGV[2] = reservationId
--
-- Returns 1 if a unit was returned, 0 if there was nothing to release.

local hold = redis.call('GET', KEYS[3])
if (not hold) or hold ~= ARGV[2] then
  return 0
end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[3])
if KEYS[4] and KEYS[4] ~= '' then
  redis.call('DEL', KEYS[4])
end
return 1
