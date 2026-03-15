-- Acquire Lock with Fencing Token
-- KEYS[1] = lock key
-- KEYS[2] = fencing counter key (lock:{resource}:fence)
-- ARGV[1] = owner token
-- ARGV[2] = lease duration in milliseconds
--
-- Returns: {1, fencing_token} if acquired, {0, 0} if not

local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2])
if result then
    local fence = redis.call('INCR', KEYS[2])
    return {1, fence}
end
return {0, 0}
