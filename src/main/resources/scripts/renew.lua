-- Renew Lock (atomic ownership check + TTL extension)
-- KEYS[1] = lock key
-- ARGV[1] = owner token
-- ARGV[2] = new lease duration in milliseconds
--
-- Returns: 1 if renewed, 0 if not owner or key missing

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
