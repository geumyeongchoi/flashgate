// k6 run k6/chaos.js — 60초 동안 300 rps 고정. 병행으로 scripts/chaos-kill-redis-master.sh 또는 chaos-kill-app.sh 실행.
// 관심 지표: 5xx(503) 비율, p99, 초당 실패 추이. 성공 판정 없이 관측만.
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
http.setResponseCallback(http.expectedStatuses(200, 202, 409));

const latency = new Trend('reserve_latency', true);
const s503 = new Counter('status_503');
const s5xx = new Counter('status_5xx_other');
const errors = new Counter('transport_errors');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: { steady: { executor: 'constant-arrival-rate', rate: 300, timeUnit: '1s', duration: '60s', preAllocatedVUs: 100, maxVUs: 600 } },
};
const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const PRODUCT = __ENV.PRODUCT_ID || '3';

export function setup() {
  http.post(`${BASE}/api/admin/products/${PRODUCT}/stock`, JSON.stringify({ quantity: 1000000 }), { headers: { 'Content-Type': 'application/json' } });
  return { run: Date.now().toString(36) };
}
export default function (data) {
  const userId = `c-${data.run}-${__VU}-${__ITER}`;
  const res = http.post(`${BASE}/api/orders`, JSON.stringify({ productId: Number(PRODUCT), userId }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${userId}-${PRODUCT}` }, timeout: '5s' });
  latency.add(res.timings.duration);
  if (res.status === 0) errors.add(1);
  else if (res.status === 503) s503.add(1);
  else if (res.status >= 500) s5xx.add(1);
}
export function handleSummary(data) {
  const v = (n, k) => (data.metrics[n] && data.metrics[n].values[k] != null) ? data.metrics[n].values[k] : 0;
  const total = v('http_reqs', 'count');
  const bad = v('status_503', 'count') + v('status_5xx_other', 'count') + v('transport_errors', 'count');
  const md = `# chaos ${__ENV.CHAOS || ''} ${new Date().toISOString()}\n\n| 지표 | 값 |\n|---|---|\n| 요청 수 | ${total} |\n| 503(서킷/재시도 소진) | ${v('status_503', 'count')} |\n| 기타 5xx | ${v('status_5xx_other', 'count')} |\n| 전송 오류(타임아웃 등) | ${v('transport_errors', 'count')} |\n| 최종 실패율 | ${total ? (bad * 100 / total).toFixed(3) : 0}% |\n| p50 | ${v('reserve_latency', 'med').toFixed(2)} ms |\n| p99 | ${v('reserve_latency', 'p(99)').toFixed(2)} ms |\n| max | ${v('reserve_latency', 'max').toFixed(2)} ms |\n`;
  return { [`k6/results/chaos-${__ENV.CHAOS || 'run'}-latest.md`]: md, stdout: md };
}
