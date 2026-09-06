#!/usr/bin/env bash
# k6 부하 중 실행: Redis master 강제 종료 → Sentinel failover 시간·앱 에러율 관찰
set -euo pipefail
cd "$(dirname "$0")/.."
echo "[$(date +%T)] killing redis-master"; docker compose kill redis-master
for i in $(seq 1 30); do
  m=$(docker compose exec -T sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster | head -1)
  echo "[$(date +%T)] master=$m"; [[ "$m" != "redis-master" && -n "$m" ]] && { echo "failover done in ~${i}s"; break; }
  sleep 1
done
sleep 20; echo "[$(date +%T)] restarting old master as replica"; docker compose up -d redis-master
