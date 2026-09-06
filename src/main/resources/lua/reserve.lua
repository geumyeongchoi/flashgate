-- reserve.lua — 재고 확인 + 차감 + 유저 중복 방지 + 예약 기록을 1회 왕복으로 원자 처리
-- KEYS[1] = stock:{productId}            (string, 남은 재고)
-- KEYS[2] = reserved:{productId}         (set, 예약한 userId)
-- KEYS[3] = reservation:{orderId}        (hash, 예약 레코드)
-- ARGV[1] = userId
-- ARGV[2] = orderId
-- ARGV[3] = ttlSeconds (미확정 예약 자동 만료용)
-- 반환: 1 = 성공, 0 = 품절, -1 = 이미 예약한 유저

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return -1
end

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
  return 0
end

local productId = string.sub(KEYS[1], 7)
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], 'userId', ARGV[1], 'productId', productId, 'status', 'RESERVED', 'reservedAt', redis.call('TIME')[1])
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))
return 1
