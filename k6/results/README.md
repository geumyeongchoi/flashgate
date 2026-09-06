# k6 결과 인덱스

환경: MacBook Pro(Apple Silicon) · Docker Desktop 4.63 (Engine 29) · 앱 2대(profile=sentinel) + Redis Sentinel 3 + Kafka + MySQL + Traefik · k6 k6 v2.2.0 (commit/devel, go1.26.5, darwin/arm64)

## spike

| 지표 | 값 |
|---|---|
| 예약 성공 | 110 (기대 100) |
| 품절 응답 | 15039 |
| 5xx 비율 | 0.000% |
| p50 | 1.55 ms |
| p95 | 7.93 ms |
| p99 | 27.55 ms |
| max | 439.50 ms |
| 처리량 | 1496 rps |

## ramp

| 지표 | 값 |
|---|---|
| 요청 수 | 39001 |
| 처리량 | 709 rps |
| 5xx 비율 | 0.000% |
| p50 | 2.03 ms |
| p95 | 20.70 ms |
| p99 | 27.25 ms |
| max | 53.36 ms |

## chaos-redis

| 지표 | 값 |
|---|---|
| 요청 수 | 18001 |
| 503(서킷/재시도 소진) | 1021 |
| 기타 5xx | 0 |
| 전송 오류(타임아웃 등) | 0 |
| 최종 실패율 | 5.672% |
| p50 | 1.86 ms |
| p99 | 203.20 ms |
| max | 220.95 ms |

## chaos-app

| 지표 | 값 |
|---|---|
| 요청 수 | 18002 |
| 503(서킷/재시도 소진) | 0 |
| 기타 5xx | 13 |
| 전송 오류(타임아웃 등) | 5 |
| 최종 실패율 | 0.100% |
| p50 | 2.00 ms |
| p99 | 35.23 ms |
| max | 5000.48 ms |

