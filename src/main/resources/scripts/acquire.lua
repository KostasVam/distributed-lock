-- Acquire Lock
-- KEYS[1] = lock key
-- ARGV[1] = owner token
-- ARGV[2] = lease duration in milliseconds
--
-- Returns: 1 if acquired, 0 if not

local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if result then
    return 1
end
return 0
