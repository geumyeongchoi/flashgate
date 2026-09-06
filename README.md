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

## 3. 실측 결과 (2026-09-06 · MacBook Pro Apple Silicon · Docker Desktop · 앱 2대 + Sentinel 3 + Traefik, k6 v2.2)

원본: `k6/results/*.md`. 모든 수치는 로컬 Docker 환경 값이며 서버 배포 후 재측정한다(HARD-GATE #4: 추정치 기재 금지).

| 시나리오 | 요청 | 처리량 | 5xx | p50 | p95 | p99 | 비고 |
|---|---|---|---|---|---|---|---|
| spike — 재고 100, 5,000명 스파이크 10s | 15,149 | 1,496 rps | 0 | 1.6 ms | 7.9 ms | 27.6 ms | 예약 성공 110 = 100 + 결제 거절(5%) 보상으로 되돌아온 재고 재판매. **초과판매 0** (Redis 잔여 0 · reserved 100) |
| ramp — 100→1,000 rps 55s, 재고 10만 | 39,001 | 709 rps | 0 | 2.0 ms | 20.7 ms | 27.3 ms | k6 arrival-rate 가 VU 제한으로 709 rps 에서 포화(측정 클라이언트 한계) |
| chaos — **Redis master kill** 중 300 rps 60s | 18,001 | 300 rps | 503 ×1,021 (5.7%) | 1.9 ms | — | 203 ms | 실패는 전부 503 + Retry-After(빠른 실패), 500·타임아웃 0. 실패 창 ≈ 3.4s (down-after 2s + 선출). 1차 시도(down-after 5s, 예외 매핑 전)는 500 ×2,433 (13.5%) |
| chaos — **앱 1대 SIGKILL** 중 300 rps 60s | 18,002 | 300 rps | 18 (0.10%) | 2.0 ms | — | 35 ms | Traefik retry 미들웨어(다른 서버로 1회 재시도) 적용 후. 적용 전 1.5% |

동시성 정확성은 통합 테스트로 고정: 가상 스레드 1,000개 동시 예약 → **202 정확히 100 · 409 900 · 초과판매 0**, 이후 MySQL CONFIRMED 100, 대사 일치 (`ReserveFlowIntegrationTest`, 1.9s).

### 설계 판단 — 대안 비교

| 방식 | 정확성 | 지연 특성 | 상태 |
|---|---|---|---|
| **Redis Lua 원자 차감 (채택)** | ✔ 초과판매 0 실측 | 왕복 1회, 락 없음 — p50 1.6~2.0 ms | 구현·측정 완료 |
| DB `SELECT … FOR UPDATE` | ✔ | 커넥션 풀 고갈·락 대기가 트래픽에 비례 | 비교 구현 예정(Day 8 잔여) |
| Redisson 분산락 + DB | ✔ | 락 획득 대기 + 2 왕복 | 비교 구현 예정(Day 8 잔여) |
| Redis `DECR` 만 | ✘ 음수 재고 | — | 비교용 실패 사례 |

장애 대응 설계에서 얻은 것
- **failover 창의 실패는 "빠른 503"으로 바꾸는 것이 목표**이고 0으로 만드는 것이 목표가 아니다. Retry(50→100→200ms)는 수 초의 failover 를 덮을 수 없으므로, 남는 요청은 503 + Retry-After 로 즉시 돌려보내 큐잉·지연 폭증을 막는다. 클라이언트 재시도가 멱등키로 안전하다.
- Sentinel `down-after-milliseconds` 5s→2s 로 실패 창을 절반 이하로. 더 줄이면 오탐 failover 위험.
- 예외 매핑이 빠지면 같은 장애가 500 으로 보인다(1차 13.5% → 2차 5.7%, 전부 503). READONLY replica 쓰기·타임아웃·연결 끊김을 Retry/CB 대상과 problem+json 503 으로 명시.
- 인스턴스 SIGKILL 은 Traefik 헬스체크(1s)만으로는 1.5% 실패 → 멱등키가 있으니 **프록시 재시도 1회**를 켜서 0.1%.

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
- "왜 Lua인가" → 확인+차감+기록을 원자화해 락 없이 정확(초과판매 0 실측). p50 1.6ms.
- "Redis가 죽으면?" → Sentinel failover 창 ≈3.4s, 그 안의 요청은 503+Retry-After 로 빠른 실패(5.7%), 500·타임아웃 0. 예외 매핑 전엔 500 13.5%.
- "중복 주문은?" → Lua에서 유저 중복 체크 + 멱등키 + 컨슈머 유니크 제약, 3중.
- "재고 정합성은 어떻게 보장?" → 아웃박스 at-least-once + 보상 트랜잭션 + 1분 대사.
