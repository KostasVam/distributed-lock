-- Release Lock (atomic ownership check + delete)
-- KEYS[1] = lock key
-- ARGV[1] = owner token
--
-- Returns: 1 if released, 0 if key missing, -1 if token mismatch

local current = redis.call('GET', KEYS[1])
if current == false then
    return 0
end
if current == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return -1
