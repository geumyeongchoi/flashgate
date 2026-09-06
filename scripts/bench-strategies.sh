#!/usr/bin/env bash
# 전략별(lua / dblock / redisson) spike 벤치마크. 각 전략으로 앱을 재기동하고 k6 spike 를 3회 실행, 결과를 k6/results/strategy-<name>-N.md 로 저장.
set -euo pipefail
cd "$(dirname "$0")/.."
RUNS="${RUNS:-3}"
for strat in lua dblock redisson; do
  echo "▶ strategy=$strat"
  FLASHGATE_STRATEGY=$strat docker compose up -d --force-recreate app-1 app-2 >/dev/null 2>&1
  for i in $(seq 1 40); do curl -sf http://localhost:8090/actuator/health >/dev/null 2>&1 && break; sleep 3; done
  sleep 5
  for n in $(seq 1 "$RUNS"); do
    PRODUCT_ID=$((900 + n)) k6 run --quiet k6/spike.js >/dev/null 2>&1 || true
    cp k6/results/spike-latest.md "k6/results/strategy-$strat-$n.md"
    echo "  run $n: $(grep -E 'p50|p99|예약 성공|5xx' k6/results/spike-latest.md | tr -d '|' | tr '\n' ' ')"
  done
done
FLASHGATE_STRATEGY=lua docker compose up -d --force-recreate app-1 app-2 >/dev/null 2>&1
