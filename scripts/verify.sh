#!/usr/bin/env bash
# verify.sh --fast : compile + ktlint + 단위 테스트 (Docker 불필요, 수십 초)
# verify.sh --full : + Testcontainers 통합 테스트
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:---fast}"

step() { echo; echo "▶ $1"; }

step "compile";  ./gradlew -q compileKotlin compileTestKotlin
step "ktlint";   ./gradlew -q ktlintCheck
if [[ "$MODE" == "--fast" ]]; then
  step "unit tests"; ./gradlew -q test -PexcludeTags=integration
else
  if ! docker info >/dev/null 2>&1; then echo "✗ Docker 가 필요합니다 (--full)"; exit 2; fi
  step "all tests (incl. Testcontainers)"; ./gradlew -q test
fi
echo; echo "✓ verify $MODE passed"
