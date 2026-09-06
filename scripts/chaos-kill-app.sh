#!/usr/bin/env bash
# k6 부하 중 실행: 앱 1대 강제 종료(SIGKILL, graceful 아님) → Traefik 헬스체크 제외까지의 에러 수 관찰
set -euo pipefail
cd "$(dirname "$0")/.."
echo "[$(date +%T)] kill app-2"; docker compose kill app-2
sleep 20
echo "[$(date +%T)] restart app-2"; docker compose up -d app-2
