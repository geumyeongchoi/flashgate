# Day 7 — 아웃박스 → Kafka → 확정 컨슈머 + Sentinel failover 흡수

## 목표
예약된 주문이 비동기로 CONFIRMED/CANCELLED가 되고, Redis master가 죽어도 요청 실패율이 목표(<1%) 안이다.

## 범위
1. `OutboxRelay`: `@Scheduled(fixedDelay=100ms)` → `SELECT … WHERE published_at IS NULL ORDER BY id LIMIT 200 FOR UPDATE SKIP LOCKED` → Kafka 발행(key=orderId) → `published_at` 갱신. 앱 2대가 동시에 돌아도 중복 발행 최소화(SKIP LOCKED), 중복돼도 컨슈머가 멱등.
2. `OrderReservedConsumer`(topic `order.reserved`, 파티션 6, group `order-confirm`):
   - 모의 PG `MockPaymentGateway`: 50~200ms 지연, 5% 실패(설정)
   - 성공 → `orders` INSERT(idempotency_key unique로 중복 무해) + Redis 예약 status=CONFIRMED + `order.confirmed` 발행
   - 실패 → `compensate.lua` + `order.cancelled` 발행
   - 처리 실패 시 재시도 3회 후 DLT(`order.reserved.DLT`)
3. `ReconcileJob`(1분): `초기재고 == Redis잔여 + CONFIRMED수 + RESERVED수(미확정)` 검증, 불일치 시 `flashgate_stock_mismatch` 게이지 1
4. Sentinel 대응: Lettuce `ReadFrom.MASTER`(재고는 마스터만), 토폴로지 갱신 확인. Retry/CircuitBreaker 동작 로그 + 메트릭(`resilience4j_circuitbreaker_state`)
5. Graceful shutdown: 진행 중 요청 완료 + 컨슈머 오프셋 커밋 후 종료(readiness false → 30s)

## 테스트 시나리오
- [통합, Testcontainers Kafka+MySQL+Redis] 예약 10건 → awaitility 5s 내 orders 10행, 상태 CONFIRMED (실패율 0 설정)
- [통합] 실패율 100% 설정 → 전부 CANCELLED, Redis 재고 == 초기값
- [통합] 같은 `order.reserved` 메시지 2회 소비 → orders 1행
- [통합] Reconcile: 임의로 Redis 재고 -1 조작 → mismatch 게이지 1

## DoD
- `k6 run k6/spike.js` 병행 + `scripts/chaos-kill-redis-master.sh` → failover 초, 5xx 비율, 서킷 오픈 횟수를 `k6/results/chaos-redis-latest.md`에 기록
- 결과가 1% 초과면 down-after-milliseconds·retry 파라미터 조정 후 재측정(조정 근거를 README "설계 판단"에)
