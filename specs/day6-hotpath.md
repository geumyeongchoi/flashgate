# Day 6 — 핫패스: Lua 원자 예약 + 멱등키 + 동시성 테스트

## 목표
동시 1,000 요청에 재고 100이면 **정확히 100건**만 202, 나머지 409. 유저 중복 0.

## 범위
1. Flyway `V1`: `products(id, name, initial_stock)`, `orders(id char(36) pk, product_id, user_id, status, idempotency_key unique, created_at, confirmed_at)`, `outbox(id bigint auto, aggregate_id, type, payload json, created_at, published_at null, index(published_at))`
2. `POST /api/admin/products/{id}/stock {quantity}` → MySQL products upsert + `StockRedisRepository.initStock`
3. `POST /api/orders {productId, userId}` + 헤더 `Idempotency-Key`
   - 멱등: Redis `idem:{key}` SETNX(TTL 24h) → 이미 있으면 저장된 응답 재반환
   - `reserve.lua` 실행 → RESERVED면 outbox INSERT(`order.reserved`) 1건 + 202 `{orderId, status: RESERVED}`
   - SOLD_OUT → 409 problem+json `type=sold-out`, ALREADY_RESERVED → 409 `type=duplicate-user`
   - StockUnavailableException → 503 + `Retry-After: 1`
4. `GET /api/orders/{id}` → Redis 예약 해시 우선, 없으면 MySQL
5. 에러 핸들러: RFC 9457 `application/problem+json`
6. Lua 스크립트 기동 시 `SCRIPT LOAD` 프리로드(첫 요청 지연 제거)

## 테스트 시나리오 (먼저 작성)
- [단위, Lua 로직은 Testcontainers Redis] reserve: 재고 1 → 두 유저 순차 호출 → 1, 0
- [단위] reserve: 같은 유저 2회 → 1, -1 / compensate 후 재고 복원, 두 번째 compensate는 0
- [통합] 1,000 가상 스레드 동시 `POST /api/orders`(유저 모두 다름), 재고 100 → 202 == 100, 409 == 900, Redis `stock` == 0, `reserved` set size == 100
- [통합] 같은 Idempotency-Key 2회 → 동일 orderId, Lua 호출 1회(MockK spy 또는 reserved set size)
- [ArchUnit] `infra.redis` 외 패키지에서 `StringRedisTemplate.opsForValue().increment/decrement` 호출 금지

## DoD
- `verify.sh --full` 통과, 동시성 테스트 3회 연속 성공
- `docker compose up` 후 `k6 run k6/spike.js` 1회 실행 → `k6/results/spike-latest.md` 커밋(첫 기준선)
