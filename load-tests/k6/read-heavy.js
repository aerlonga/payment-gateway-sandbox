// Cenario 4: leitura pesada. O setup popula pagamentos para varios clientes;
// o teste le GET /payments?customerId=X (indice (customer_id, status)) e GET /payments/{id}.
//   k6 run load-tests/k6/read-heavy.js
//   k6 run -e CUSTOMERS=200 -e PER_CUSTOMER=50 -e RATE=300 load-tests/k6/read-heavy.js
import http from 'k6/http';
import { check } from 'k6';
import { PAYMENTS_URL, uuid, paymentBody, createParams } from './lib/config.js';

const CUSTOMERS = Number(__ENV.CUSTOMERS || 100);
const PER_CUSTOMER = Number(__ENV.PER_CUSTOMER || 30);
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '1m';
const SEED_BATCH = 50;
const STATUSES = ['CAPTURED', 'FAILED'];

export const options = {
  setupTimeout: '10m',
  scenarios: {
    read: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:list}': ['p(95)<300', 'p(99)<600'],
    'http_req_duration{endpoint:get}': ['p(95)<150', 'p(99)<300'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const customers = Array.from({ length: CUSTOMERS }, () => uuid());
  const seeds = [];
  customers.forEach((c) => {
    for (let i = 0; i < PER_CUSTOMER; i++) seeds.push(c);
  });

  const paymentIds = [];
  for (let i = 0; i < seeds.length; i += SEED_BATCH) {
    const chunk = seeds.slice(i, i + SEED_BATCH);
    const responses = http.batch(
      chunk.map((c) => ['POST', PAYMENTS_URL, paymentBody(c), createParams(`k6-seed-${uuid()}`)]),
    );
    responses.forEach((r) => {
      if (r.status === 201) paymentIds.push(r.json('id'));
    });
  }
  console.log(`seed: ${paymentIds.length}/${seeds.length} pagamentos para ${CUSTOMERS} clientes`);
  return { customers, paymentIds };
}

function pick(list) {
  return list[Math.floor(Math.random() * list.length)];
}

export default function (data) {
  const roll = Math.random();
  let res;
  if (roll < 0.6) {
    res = http.get(`${PAYMENTS_URL}?customerId=${pick(data.customers)}&size=20`, { tags: { endpoint: 'list' } });
  } else if (roll < 0.8) {
    res = http.get(`${PAYMENTS_URL}?customerId=${pick(data.customers)}&status=${pick(STATUSES)}`, {
      tags: { endpoint: 'list' },
    });
  } else {
    res = http.get(`${PAYMENTS_URL}/${pick(data.paymentIds)}`, { tags: { endpoint: 'get' } });
  }
  check(res, { 'status 200': (r) => r.status === 200 });
}
