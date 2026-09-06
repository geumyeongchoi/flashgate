-- compensate.lua — 결제 실패/취소 시 재고 복원 (멱등: 이미 취소된 예약은 무시)
-- KEYS[1] = stock:{productId}
-- KEYS[2] = reserved:{productId}
-- KEYS[3] = reservation:{orderId}
-- ARGV[1] = userId
-- 반환: 1 = 복원, 0 = 이미 취소/없음

local status = redis.call('HGET', KEYS[3], 'status')
if status ~= 'RESERVED' then
  return 0
end

redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('HSET', KEYS[3], 'status', 'CANCELLED')
return 1
