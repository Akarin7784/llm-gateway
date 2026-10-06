-- Token bucket, atomic per key. Mirrors TokenBucketMath; only the storage and the atomicity differ,
-- which is why the Java implementation is unit-tested against the same arithmetic.
--
-- KEYS[1] bucket key
-- ARGV[1] capacity, ARGV[2] refill per minute, ARGV[3] permits, ARGV[4] key ttl seconds
--
-- Returns {allowed(0|1), remaining-as-string, retry_after_millis}
-- The remaining count is stringified on purpose: Redis converts table numbers to integers and would
-- silently truncate the fractional token balance.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_minute = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

-- One clock for every gateway replica. Using each replica's own wall clock makes the bucket drift
-- with clock skew between hosts.
local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local last = tonumber(state[2])
if tokens == nil then
  tokens = capacity
  last = now_ms
end

if now_ms > last then
  local elapsed_seconds = (now_ms - last) / 1000.0
  tokens = tokens + elapsed_seconds * (refill_per_minute / 60.0)
  if tokens > capacity then
    tokens = capacity
  end
  last = now_ms
end

local allowed = 0
local retry_after = 0
if tokens >= permits then
  tokens = tokens - permits
  allowed = 1
else
  local missing = permits - tokens
  retry_after = math.ceil(missing / (refill_per_minute / 60.0) * 1000)
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(last))
redis.call('EXPIRE', key, ttl)
return { allowed, tostring(tokens), retry_after }
