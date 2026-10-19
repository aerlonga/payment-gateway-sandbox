// Cenario 1: criacao de cobranca sob concorrencia (Idempotency-Key sempre diferente).
// Rodar com o provider fake (latencia simulada), nunca contra o Stripe.
//   k6 run load-tests/k6/create-payment.js
//   k6 run -e RATE=100 -e DURATION=2m load-tests/k6/create-payment.js
import http from 'k6/http';
import { check } from 'k6';
import { PAYMENTS_URL, uuid, paymentBody, createParams } from './lib/config.js';

const RATE = Number(__ENV.RATE || 25);
const DURATION = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    create: {
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
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const res = http.post(PAYMENTS_URL, paymentBody(uuid()), createParams(`k6-${uuid()}`));
  check(res, {
    'status 201': (r) => r.status === 201,
    'tem id': (r) => !!r.json('id'),
  });
}
