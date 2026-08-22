import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

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
    const idempotencyKey = `contention-${__VU}-${__ITER}-${Date.now()}`;
    const payload = JSON.stringify({ cardId: 'CARD-LOAD-001', amount: 1 });
    const res = http.post(`${BASE_URL}/api/v1/payments/authorize`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`,
            'Idempotency-Key': idempotencyKey,
        },
    });
    check(res, { 'status is 200': (r) => r.status === 200 });
}
