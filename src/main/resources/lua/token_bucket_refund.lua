-- Returns unused permits to the bucket after settling against real usage.
-- KEYS[1] bucket key; ARGV[1] capacity, ARGV[2] refill per minute, ARGV[3] permits to return,
-- ARGV[4] key ttl seconds
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_minute = tonumber(ARGV[2])
local permits = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local t = redis.call('TIME')
local now_ms = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(state[1])
local last = tonumber(state[2])
if tokens == nil then
  -- Nothing to refund into: the window rolled over, so the bucket is full anyway.
  return tostring(capacity)
end

if now_ms > last then
  local elapsed_seconds = (now_ms - last) / 1000.0
  tokens = tokens + elapsed_seconds * (refill_per_minute / 60.0)
  last = now_ms
end

tokens = tokens + permits
if tokens > capacity then
  tokens = capacity
end

redis.call('HSET', key, 'tokens', tostring(tokens), 'ts', tostring(last))
redis.call('EXPIRE', key, ttl)
return tostring(tokens)
