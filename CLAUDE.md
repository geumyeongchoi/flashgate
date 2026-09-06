# flashgate — 에이전트 작업 규칙 (SSOT)

## 스택
- Kotlin 2.2, JDK 21(가상 스레드 `spring.threads.virtual.enabled=true`), Spring Boot 3.5, Gradle Kotlin DSL
- Redis 7 Sentinel(Lettuce), Kafka(spring-kafka), MySQL 8.4 + JdbcClient(Spring JDBC), Flyway — jOOQ는 Day 8 이후 도입 검토(codegen에 DB 필요)
- Resilience4j, Micrometer + OTel, k6
- 테스트: Kotest + MockK + Testcontainers(redis, kafka, mysql), ArchUnit, ktlint

## HARD-GATE
1. `main` 직접 push 금지.
2. 재고를 바꾸는 코드는 **오직** `reserve.lua` / `compensate.lua` 안에서만. Kotlin 코드에서 `DECR`/`INCR` 직접 호출 금지(ArchUnit + grep 훅).
3. 핫패스(`POST /api/orders`)에서 DB 동기 호출은 outbox INSERT 1건만. JPA/트랜잭션 확장 금지.
4. 성능 수치는 반드시 k6 결과 파일(`k6/results/*.md`)에서 인용. 추정치 README 기재 금지.
5. 변경 후 `scripts/verify.sh --fast` 통과 전 커밋 금지.

## 구조
```
dev.gychoi.flashgate
├─ api/            OrderController, AdminController, ErrorHandler(RFC 9457 problem+json)
├─ application/    ReserveOrderUseCase, ConfirmOrderUseCase, ReconcileUseCase
├─ domain/         Order, Stock, Reservation, 상태머신 — 프레임워크 의존 없음
├─ infra/
│   ├─ redis/      LuaScripts(reserve/compensate), StockRepository(Redis)
│   ├─ outbox/     OutboxRepository(JdbcClient), OutboxRelay(@Scheduled 100ms, SKIP LOCKED)
│   ├─ kafka/      Producer, OrderReservedConsumer
│   └─ resilience/ Retry/CircuitBreaker 설정, fallback
└─ config/
```
- 지연에 민감한 코드는 로그를 `DEBUG`로만. 핫패스에서 문자열 포맷·리플렉션 금지.

## 작업 방식
- `specs/dayN.md`의 테스트 시나리오를 먼저 테스트로 작성 → 구현.
- 동시성 테스트는 `@Tag("integration")`, 부하 테스트는 k6 스크립트로만(JUnit에서 부하 금지).
- 같은 오류 3회 반복 시 중단·보고. 에이전트 서명 커밋 금지.

## 검증
- `scripts/verify.sh --fast|--full`
- `k6 run k6/<scenario>.js` — 실행 전 `docker compose ps` 로 앱 2대·Redis master 확인
