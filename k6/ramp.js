// k6 run k6/ramp.js — 100 → 1,000 rps 램프업 30s, 재고 100,000(품절 없이 순수 지연 측정)
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const latency = new Trend('reserve_latency', true);

export const options = {
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
}

export default function () {
  const userId = `r-${__VU}-${__ITER}`;
  const res = http.post(`${BASE}/api/orders`, JSON.stringify({ productId: Number(PRODUCT), userId }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${userId}-${PRODUCT}` } });
  latency.add(res.timings.duration);
  check(res, { 'accepted': (r) => r.status === 202 });
}

export function handleSummary(data) {
  const md = `# ramp ${new Date().toISOString()}\n\n| 지표 | 값 |\n|---|---|\n| 요청 수 | ${data.metrics.http_reqs.values.count} |\n| 처리량 | ${data.metrics.http_reqs.values.rate.toFixed(0)} rps |\n| 5xx 비율 | ${(data.metrics.http_req_failed.values.rate * 100).toFixed(3)}% |\n| p50 | ${data.metrics.reserve_latency.values['p(50)'].toFixed(2)} ms |\n| p95 | ${data.metrics.reserve_latency.values['p(95)'].toFixed(2)} ms |\n| p99 | ${data.metrics.reserve_latency.values['p(99)'].toFixed(2)} ms |\n| max | ${data.metrics.reserve_latency.values.max.toFixed(2)} ms |\n`;
  return { 'k6/results/ramp-latest.md': md, stdout: md };
}
