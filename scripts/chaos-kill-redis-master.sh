#!/usr/bin/env bash
# k6 부하 중 실행: Redis master 강제 종료 → Sentinel failover 시간·앱 에러율 관찰
set -euo pipefail
cd "$(dirname "$0")/.."
before=$(docker compose exec -T sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster | head -1)
echo "[$(date +%T)] killing redis-master (current master ip=$before)"; docker compose kill redis-master
now_ms() { python3 -c "import time; print(int(time.time()*1000))"; }
start=$(now_ms)
for i in $(seq 1 60); do
  m=$(docker compose exec -T sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster | head -1)
  if [[ -n "$m" && "$m" != "$before" ]]; then echo "[$(date +%T)] new master=$m — failover took $(( $(now_ms) - start )) ms"; break; fi
  sleep 0.5
done
sleep 20; echo "[$(date +%T)] restarting old master as replica"; docker compose up -d redis-master
