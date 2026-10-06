-- In-flight slot with an expiring lease, one sorted set per tenant.
-- A replica that dies mid-stream cannot run its release, so slots are reclaimed by expiry rather
-- than trusting a counter that only ever goes down on the happy path.
--
-- KEYS[1] zset key; ARGV[1] limit, ARGV[2] lease id, ARGV[3] lease ttl millis
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local lease = ARGV[2]
local ttl = tonumber(ARGV[3])

local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

redis.call('ZREMRANGEBYSCORE', key, 0, now_ms)

if redis.call('ZCARD', key) < limit then
  redis.call('ZADD', key, now_ms + ttl, lease)
  redis.call('PEXPIRE', key, ttl * 2)
  return 1
end
return 0
