# Day 8 — 대안 비교 벤치마크 + 앱 카오스 + 결과 표

## 목표
README의 빈칸(__)을 전부 실측값으로 채운다. "왜 Lua인가"에 숫자로 답한다.

## 범위
1. 비교 구현(프로파일 `strategy=lua|redisson|dblock`로 전환, 코드 분리는 `application.strategy` 패키지):
   - `dblock`: `SELECT stock FROM products WHERE id=? FOR UPDATE` → 차감 → 커밋
   - `redisson`: `RLock` tryLock(50ms) → GET/DECR → unlock (의존성 `org.redisson:redisson-spring-boot-starter`)
   - `lua`: 기존
2. `k6/ramp.js`(100→1,000 rps 램프업 30s), `k6/spike.js` 각 전략 3회 실행, 중앙값 채택
3. `scripts/chaos-kill-app.sh` 병행 → Traefik 제외까지 5xx 건수, 복구 후 재합류 확인
4. 가상 스레드 on/off(`spring.threads.virtual.enabled`) p99 비교 1행
5. 결과 표 → README §3 + `k6/results/README.md`(실행 환경: CPU/RAM/도커 버전 명기)

## DoD
- README 대안 비교 표 전부 실측값, 각 셀 옆에 결과 파일 링크
- 초과판매 0건이 세 전략 모두에서 확인(정확성은 같고 지연만 다름 → 설계 판단의 핵심 문장)
- `v0.1.0` 태그
