// Cenario 2: a MESMA Idempotency-Key disparada em paralelo.
// Esperado por iteracao: exatamente um 201, os demais 200 com o mesmo id, nenhum 5xx.
//   k6 run load-tests/k6/idempotency-race.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { PAYMENTS_URL, uuid, paymentBody, createParams } from './lib/config.js';

const PARALLEL = Number(__ENV.PARALLEL || 10);
const ITERATIONS = Number(__ENV.ITERATIONS || 200);

const duplicatedCreations = new Counter('idempotency_duplicated_creations');
const divergentIds = new Counter('idempotency_divergent_ids');

export const options = {
  scenarios: {
    race: {
      executor: 'shared-iterations',
      vus: 20,
      iterations: ITERATIONS,
      maxDuration: '5m',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    idempotency_duplicated_creations: ['count==0'],
    idempotency_divergent_ids: ['count==0'],
    checks: ['rate==1'],
  },
};

export default function () {
  const key = `k6-race-${uuid()}`;
  const body = paymentBody(uuid());
  const requests = Array.from({ length: PARALLEL }, () => ['POST', PAYMENTS_URL, body, createParams(key)]);
  const responses = http.batch(requests);

  const created = responses.filter((r) => r.status === 201).length;
  const replays = responses.filter((r) => r.status === 200).length;
  const ids = new Set(responses.filter((r) => r.status < 300).map((r) => r.json('id')));

  if (created > 1) duplicatedCreations.add(created - 1);
  if (ids.size > 1) divergentIds.add(ids.size - 1);

  check(responses, {
    'exatamente um 201': () => created === 1,
    'demais respostas 200 (replay)': () => replays === PARALLEL - 1,
    'um unico paymentId': () => ids.size === 1,
  });
}
