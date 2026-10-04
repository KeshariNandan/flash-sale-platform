#!/usr/bin/env bash
# Create an OPEN sale and a numbered buyer pool (lt-0 .. lt-N-1) for k6.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8085}"
STOCK="${STOCK:-1000}"
BUYER_POOL="${BUYER_POOL:-50000}"
HOLD_TTL="${HOLD_TTL:-3600}"
PG_CONTAINER="${PG_CONTAINER:-fsp-postgres}"
SKU="${SKU:-SKU-LT-$(date +%s)}"

wait_for_app() {
	echo "waiting for ${BASE_URL}/actuator/health"
	for _ in $(seq 1 90); do
		if curl -sf "${BASE_URL}/actuator/health" >/dev/null; then
			return 0
		fi
		sleep 2
	done
	echo "app did not become healthy at ${BASE_URL}" >&2
	exit 1
}

iso_now() {
	python3 -c 'from datetime import datetime, timezone; print(datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))'
}

iso_plus_hours() {
	python3 -c 'from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc)+timedelta(hours=2)).strftime("%Y-%m-%dT%H:%M:%SZ"))'
}

wait_for_app

body=$(python3 - <<PY
import json
print(json.dumps({
    "sku": "${SKU}",
    "name": "Load-test drop",
    "unitPrice": 19.99,
    "startAt": "$(iso_now)",
    "endAt": "$(iso_plus_hours)",
    "initialStock": ${STOCK},
    "perUserLimit": 1,
    "holdTtlSeconds": ${HOLD_TTL},
}))
PY
)

created="$(curl -sf -X POST "${BASE_URL}/api/v1/admin/sales" \
	-H 'Content-Type: application/json' -d "${body}")"
SALE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"${created}")"
curl -sf -X POST "${BASE_URL}/api/v1/admin/sales/${SALE_ID}/open" >/dev/null

last=$((BUYER_POOL - 1))
docker exec -i "${PG_CONTAINER}" psql -U flashsale -d flashsale -v ON_ERROR_STOP=1 <<SQL
INSERT INTO app_user (user_id, email, saved_payment_token)
SELECT 'lt-' || g, 'lt-' || g || '@load.test', 'tok'
FROM generate_series(0, ${last}) AS g
ON CONFLICT (user_id) DO NOTHING;
SQL

mkdir -p "${ROOT}/load-test/results"
cat > "${ROOT}/load-test/results/sale.env" <<EOF
export SALE_ID=${SALE_ID}
export BASE_URL=${BASE_URL}
export STOCK=${STOCK}
export BUYER_POOL=${BUYER_POOL}
EOF

echo "seeded sale ${SALE_ID} stock=${STOCK} buyers=0..${last}"
echo "source ${ROOT}/load-test/results/sale.env"
