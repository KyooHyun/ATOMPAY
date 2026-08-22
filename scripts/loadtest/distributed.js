import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const CARD_COUNT = 100;

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '30s',
};

export function setup() {
    const res = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ username: 'admin', password: 'password123' }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    check(res, { 'login succeeded': (r) => r.status === 200 });
    return { token: res.json('accessToken') };
}

export default function (data) {
    // Each VU pins to its own card (VU IDs are 1-based) so contention
    // is spread across up to CARD_COUNT distinct rows instead of one.
    const cardNum = ((__VU - 1) % CARD_COUNT) + 1;
    const cardId = `CARD-LOAD-${String(cardNum).padStart(3, '0')}`;
    const idempotencyKey = `distributed-${__VU}-${__ITER}-${Date.now()}`;
    const payload = JSON.stringify({ cardId, amount: 1 });
    const res = http.post(`${BASE_URL}/api/v1/payments/authorize`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`,
            'Idempotency-Key': idempotencyKey,
        },
    });
    check(res, { 'status is 200': (r) => r.status === 200 });
}
