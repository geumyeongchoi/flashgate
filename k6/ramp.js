// k6 run k6/ramp.js — 100 → 1,000 rps 램프업 30s, 재고 100,000(품절 없이 순수 지연 측정)
import http from 'k6/http';
import { check } from 'k6';
http.setResponseCallback(http.expectedStatuses(200, 202, 409));
import { Trend } from 'k6/metrics';

const latency = new Trend('reserve_latency', true);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 100, timeUnit: '1s', preAllocatedVUs: 300, maxVUs: 1500,
      stages: [
        { target: 1000, duration: '30s' },
        { target: 1000, duration: '20s' },
        { target: 0, duration: '5s' },
      ],
    },
  },
  thresholds: { http_req_failed: ['rate<0.001'], reserve_latency: ['p(99)<20'] },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const PRODUCT = __ENV.PRODUCT_ID || '2';

export function setup() {
  http.post(`${BASE}/api/admin/products/${PRODUCT}/stock`, JSON.stringify({ quantity: 100000 }),
    { headers: { 'Content-Type': 'application/json' } });
  return { run: Date.now().toString(36) };   // 실행마다 고유 → 멱등키가 이전 실행과 겹치지 않게
}

export default function (data) {
  const userId = `r-${data.run}-${__VU}-${__ITER}`;
  const res = http.post(`${BASE}/api/orders`, JSON.stringify({ productId: Number(PRODUCT), userId }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${userId}-${PRODUCT}` } });
  latency.add(res.timings.duration);
  check(res, { 'accepted': (r) => r.status === 202 });
}

export function handleSummary(data) {
  const v = (name, key) => (data.metrics[name] && data.metrics[name].values[key] != null) ? data.metrics[name].values[key] : 0;
  const md = `# ramp ${new Date().toISOString()}\n\n| 지표 | 값 |\n|---|---|\n| 요청 수 | ${v('http_reqs','count')} |\n| 처리량 | ${v('http_reqs','rate').toFixed(0)} rps |\n| 5xx 비율 | ${(v('http_req_failed','rate') * 100).toFixed(3)}% |\n| p50 | ${v('reserve_latency','med').toFixed(2)} ms |\n| p95 | ${v('reserve_latency','p(95)').toFixed(2)} ms |\n| p99 | ${v('reserve_latency','p(99)').toFixed(2)} ms |\n| max | ${v('reserve_latency','max').toFixed(2)} ms |\n`;
  return { 'k6/results/ramp-latest.md': md, stdout: md };
}
