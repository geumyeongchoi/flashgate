// k6 run k6/spike.js  — 재고 100개, 유저 5,000명이 3초 동안 몰림
// 성공 판정: 201/202 정확히 100건, 409(품절) 나머지, 5xx 0건, p99 < 20ms
import http from 'k6/http';
import { check } from 'k6';
http.setResponseCallback(http.expectedStatuses(200, 202, 409));
import { Counter, Trend } from 'k6/metrics';

const reserved = new Counter('orders_reserved');
const soldOut = new Counter('orders_sold_out');
const latency = new Trend('reserve_latency', true);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 100, timeUnit: '1s', preAllocatedVUs: 500, maxVUs: 2000,
      stages: [
        { target: 2000, duration: '3s' },  // 스파이크
        { target: 2000, duration: '5s' },
        { target: 0, duration: '2s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],        // 5xx 0.1% 미만
    reserve_latency: ['p(99)<20'],          // ms
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8090';
const PRODUCT = __ENV.PRODUCT_ID || '1';

export function setup() {
  http.post(`${BASE}/api/admin/products/${PRODUCT}/stock`, JSON.stringify({ quantity: 100 }),
    { headers: { 'Content-Type': 'application/json' } });
  return { run: Date.now().toString(36) };   // 실행마다 고유 → 멱등키가 이전 실행과 겹치지 않게
}

export default function (data) {
  const userId = `u-${data.run}-${__VU}-${__ITER}`;
  const res = http.post(`${BASE}/api/orders`,
    JSON.stringify({ productId: Number(PRODUCT), userId }),
    { headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `${userId}-${PRODUCT}` } });
  latency.add(res.timings.duration);
  if (res.status === 202) reserved.add(1);
  else if (res.status === 409) soldOut.add(1);
  check(res, { 'no 5xx': (r) => r.status < 500 });
}

export function handleSummary(data) {
  const m = (name, key) => (data.metrics[name] && data.metrics[name].values[key] != null) ? data.metrics[name].values[key] : 0;
  const r = m('orders_reserved', 'count');
  const md = `# spike ${new Date().toISOString()}\n\n| 지표 | 값 |\n|---|---|\n| 예약 성공 | ${r} (기대 100) |\n| 품절 응답 | ${m('orders_sold_out', 'count')} |\n| 5xx 비율 | ${(m('http_req_failed', 'rate') * 100).toFixed(3)}% |\n| p50 | ${m('reserve_latency', 'med').toFixed(2)} ms |\n| p95 | ${m('reserve_latency', 'p(95)').toFixed(2)} ms |\n| p99 | ${m('reserve_latency', 'p(99)').toFixed(2)} ms |\n| max | ${m('reserve_latency', 'max').toFixed(2)} ms |\n| 처리량 | ${m('http_reqs', 'rate').toFixed(0)} rps |\n`;
  return { 'k6/results/spike-latest.md': md, stdout: md };
}
