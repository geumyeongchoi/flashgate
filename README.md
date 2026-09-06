# flashgate — 선착순 한정수량 주문 API (초저지연 · 고가용성)

> 재고 100개짜리 상품에 1초에 수천 명이 몰려도 **초과판매 0건, p99 20ms 이하**, 그리고 앱 인스턴스나 Redis 마스터가 죽어도 **요청이 실패하지 않는** 주문 게이트.

## 1. 문제 정의

선착순 쿠폰·한정판 주문의 병목은 항상 "재고 하나를 두고 벌어지는 경쟁"이다. 흔한 해법(DB 비관락, 분산락)은 정확하지만 락 대기 때문에 지연이 트래픽에 비례해 늘어난다. 이 프로젝트는 **핫패스를 Redis 단일 Lua 스크립트로 원자화**하고, 무거운 일(주문 확정·결제·DB 적재)은 **Kafka로 비동기 분리**하여 지연과 정확성을 동시에 잡는다. 그리고 그 구조가 장애 상황에서도 유지되는지 **카오스 테스트로 수치를 남긴다.**

## 2. 아키텍처

```
client ─▶ Traefik ─▶ [flashgate-api ×2]  (Kotlin 2.2 · Spring Boot 3.5 · JDK 21 가상 스레드)
                          │  POST /api/orders {productId, userId, idempotencyKey}
                          │
                          ▼  EVALSHA reserve.lua  (재고 확인+차감+유저 중복 체크+예약 기록 = 1 round-trip)
                    [Redis Sentinel: master + replica×2 + sentinel×3]
                          │  성공 시 outbox 테이블 INSERT (같은 트랜잭션) → 202 Accepted {orderId, status: RESERVED}
                          ▼
                    [Kafka]  order.reserved  ── outbox relay(폴링 100ms) ──▶
                          │
                          ▼
                    [order-confirm consumer]  결제 모의 승인 → MySQL orders 적재 → order.confirmed
                          │            실패 시 Redis 재고 복원(compensate.lua) + order.cancelled
                          ▼
                    GET /api/orders/{id}  → RESERVED | CONFIRMED | CANCELLED
```

**HA 장치**
- 앱: 2 인스턴스 + readiness probe + graceful shutdown(30s), Traefik 헬스체크로 죽은 인스턴스 제외
- Redis: Sentinel 자동 failover(down-after 5s). 클라이언트(Lettuce)는 Sentinel 토폴로지 갱신, failover 동안 요청은 Resilience4j **Retry(지수 백오프 3회)** + **CircuitBreaker**로 흡수, 열림 상태에선 즉시 503(빠른 실패) — 큐잉으로 지연 폭증 방지
- Kafka: 아웃박스로 at-least-once, 컨슈머는 `idempotencyKey` 유니크로 중복 무해화
- 재고 정합: 매 1분 `Redis 잔여 + MySQL 확정 + 취소 = 초기 재고` 대사 잡, 불일치 시 메트릭 알람

## 3. 설계 판단 — 대안 비교 (Day 8에 수치로 채움)

| 방식 | 정확성 | p99 (동시 1,000) | 처리량 | 비고 |
|---|---|---|---|---|
| DB `SELECT … FOR UPDATE` | ✔ | __ ms | __ rps | 커넥션 풀 고갈, 데드락 위험 |
| Redisson 분산락 + DB | ✔ | __ ms | __ rps | 락 획득 대기가 트래픽에 비례 |
| **Redis Lua 원자 차감 (채택)** | ✔ | __ ms | __ rps | 단일 라운드트립, 락 없음 |
| Redis `DECR` 만 | ✘(음수 재고) | — | — | 비교용 실패 사례 |

추가 비교: Kotlin 코루틴(WebFlux) vs **가상 스레드(MVC, 채택)** — 코드 단순성 대비 p99 차이가 미미하면 MVC 유지(측정 후 결정).

## 4. 기능 범위 (Day 6~8)

**MUST**
- 상품/재고 등록 API(관리자), 주문 예약 API, 주문 상태 조회
- `reserve.lua`, `compensate.lua` — 유저당 1개 제한, 재고 0이면 `SOLD_OUT` 즉시 응답
- 멱등키(헤더 `Idempotency-Key`) — 같은 키 재요청은 첫 응답 재반환
- 아웃박스 릴레이 + Kafka 컨슈머 + MySQL 적재
- Resilience4j Retry/CircuitBreaker/Bulkhead, Sentinel 구성 docker-compose
- k6 시나리오 3종(정상 램프업 / 스파이크 / 카오스 중 부하) + 결과 표
- Testcontainers(Redis, Kafka, MySQL) 통합 테스트, 동시성 테스트(1,000 스레드 → 성공 정확히 재고 수)

**SHOULD**
- 결제 모의 PG 연동(승인 확률 95%, 지연 50~200ms) → 실패 보상 흐름
- Grafana 대시보드: RPS, p50/p99, 서킷 상태, 재고 잔량, 컨슈머 랙

**WON'T**
- 실제 PG, 대기열(웨이팅룸) UI, 다중 리전

## 5. 실행 / 측정

```bash
# 로컬 개발(standalone Redis) — 포트는 사내 인프라와 충돌하지 않게 5xxxx 대역
docker compose up -d redis-master kafka mysql       # 56379 / 59092 / 53306
./gradlew bootRun

# HA 재현: 앱 2대(profile=sentinel) + Sentinel 3 + Traefik(8090)
./gradlew bootJar && docker compose up -d --build
k6 run k6/spike.js                        # 재고 100 · 5,000명 스파이크 → k6/results/spike-latest.md
k6 run k6/ramp.js                         # 100→1,000 rps 램프업 → k6/results/ramp-latest.md
scripts/chaos-kill-redis-master.sh        # failover 중 k6 병행
scripts/chaos-kill-app.sh                 # 앱 1대 강제 종료

scripts/verify.sh --fast | --full          # 단위·아키텍처 / + Testcontainers(redis·mysql·kafka) 통합 테스트
```

핵심 테스트: `ReserveFlowIntegrationTest` — 가상 스레드 1,000개가 동시에 재고 100개를 예약 → **202 정확히 100건 · 409 900건 · 초과판매 0**, 아웃박스→Kafka→컨슈머로 MySQL에 CONFIRMED 100건, 대사 일치.

## 6. 이력서/면접 포인트
- "왜 Lua인가" → 확인+차감+기록을 원자화해 락 없이 정확. 대안 3종과 p99 비교 표.
- "Redis가 죽으면?" → Sentinel failover __초, 그동안 Retry가 흡수한 요청 __건, 서킷 오픈 __회, 최종 실패율 __%.
- "중복 주문은?" → Lua에서 유저 중복 체크 + 멱등키 + 컨슈머 유니크 제약, 3중.
- "재고 정합성은 어떻게 보장?" → 아웃박스 at-least-once + 보상 트랜잭션 + 1분 대사.
