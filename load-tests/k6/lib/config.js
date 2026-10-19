export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const PAYMENTS_URL = `${BASE_URL}/api/v1/payments`;

export const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export function paymentBody(customerId, amount) {
  return JSON.stringify({
    customerId,
    amount: amount || 15000,
    currency: 'BRL',
    paymentMethod: 'card',
    description: 'k6 load test',
  });
}

export function createParams(idempotencyKey) {
  return { headers: { ...JSON_HEADERS, 'Idempotency-Key': idempotencyKey } };
}
