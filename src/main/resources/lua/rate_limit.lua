redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[2]))
local count = redis.call('ZCOUNT', KEYS[1], tonumber(ARGV[2]), '+inf')
if count >= tonumber(ARGV[3]) then
    return 0
end
redis.call('ZADD', KEYS[1], tonumber(ARGV[1]), ARGV[4])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
return 1
