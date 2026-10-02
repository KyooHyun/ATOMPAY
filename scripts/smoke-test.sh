#!/usr/bin/env bash
# End-to-end smoke test against a running instance (e.g. `docker compose up`).
# Exercises what unit tests can't: the real `mysql` profile, Flyway-applied
# schema under ddl-auto=validate, Spring Security, and @PathVariable binding
# over actual HTTP. Every one of those has broken before without a test noticing.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RUN_ID="smoke-$(date +%s)-$$"

fail() { echo "FAIL: $*" >&2; exit 1; }

echo "Waiting for ${BASE_URL}/actuator/health ..."
for _ in $(seq 1 60); do
  if curl -fsS "${BASE_URL}/actuator/health" 2>/dev/null | grep -q '"UP"'; then break; fi
  sleep 2
done
curl -fsS "${BASE_URL}/actuator/health" | grep -q '"UP"' || fail "app never became healthy"

TOKEN=$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"password123"}' | jq -r .accessToken)
[ -n "$TOKEN" ] && [ "$TOKEN" != "null" ] || fail "login returned no token"

# post <path> <idempotency-key> <json-body> -> prints "<status> <body>"
post() {
  curl -sS -o /tmp/smoke-body -w '%{http_code}' -X POST "${BASE_URL}$1" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $2" -d "$3"
  echo " $(cat /tmp/smoke-body)"
}
expect() { # expect <expected-status> <actual "status body">
  [ "${2%% *}" = "$1" ] || fail "expected HTTP $1, got: $2"
}

R=$(post /api/v1/payments/authorize "${RUN_ID}-auth" '{"cardId":"CARD-001","amount":10000}')
expect 200 "$R"
AUTH_ID=$(echo "${R#* }" | jq -r .authorizationId)
echo "authorized ${AUTH_ID}"

# Retry with the same key and an equal amount written differently: replay, not a new charge.
R=$(post /api/v1/payments/authorize "${RUN_ID}-auth" '{"cardId":"CARD-001","amount":10000.00}')
expect 200 "$R"
[ "$(echo "${R#* }" | jq -r .authorizationId)" = "$AUTH_ID" ] || fail "idempotent retry created a new authorization"

# Same key, different request: 422.
R=$(post /api/v1/payments/authorize "${RUN_ID}-auth" '{"cardId":"CARD-001","amount":20000}')
expect 422 "$R"

R=$(post "/api/v1/payments/${AUTH_ID}/capture" "${RUN_ID}-capture" '{"amount":8000}')
expect 200 "$R"
[ "$(echo "${R#* }" | jq -r .status)" = "CAPTURED" ] || fail "capture: $R"

R=$(post "/api/v1/payments/${AUTH_ID}/partial-refund" "${RUN_ID}-prefund" '{"amount":3000}')
expect 200 "$R"

R=$(post "/api/v1/payments/${AUTH_ID}/refund" "${RUN_ID}-refund" '{"amount":5000}')
expect 200 "$R"
[ "$(echo "${R#* }" | jq -r .status)" = "REFUNDED" ] || fail "refund: $R"

TYPES=$(curl -fsS "${BASE_URL}/api/v1/payments/${AUTH_ID}/transactions" \
  -H "Authorization: Bearer ${TOKEN}" | jq -r '[.[].transactionType] | join(",")')
[ "$TYPES" = "AUTHORIZATION,CAPTURE,PARTIAL_REFUND,REFUND" ] || fail "unexpected ledger: $TYPES"

echo "OK: lifecycle + idempotency verified against ${BASE_URL} (ledger: ${TYPES})"
