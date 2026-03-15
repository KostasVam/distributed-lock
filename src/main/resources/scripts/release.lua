-- Release Lock (atomic ownership check + delete)
-- KEYS[1] = lock key
-- ARGV[1] = owner token
--
-- Returns: 1 if released, 0 if not owner or key missing

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
