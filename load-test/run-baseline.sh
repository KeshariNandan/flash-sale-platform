#!/usr/bin/env bash
# Seed a sale, optionally warm up, then run the SQL-gate claim benchmark.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "${ROOT}"

export PATH="${ROOT}/load-test/bin:/opt/homebrew/bin:${PATH}"
BASE_URL="${BASE_URL:-http://localhost:8085}"
SCENARIO="${SCENARIO:-full}"
K6_BIN="${K6_BIN:-$(command -v k6 || true)}"

if [[ -z "${K6_BIN}" ]]; then
	echo "k6 is not on PATH. Install with: brew install k6" >&2
	exit 1
fi

if [[ ! -f "${ROOT}/load-test/results/sale.env" ]]; then
	echo "no sale.env — seeding a new sale"
	BASE_URL="${BASE_URL}" "${ROOT}/load-test/seed-sale.sh"
fi
# shellcheck disable=SC1091
source "${ROOT}/load-test/results/sale.env"

OUT="${ROOT}/load-test/results/${LABEL:-baseline}-${SCENARIO}-summary.json"
mkdir -p "${ROOT}/load-test/results"

echo "running ${SCENARIO} against sale ${SALE_ID} at ${BASE_URL}"
"${K6_BIN}" run \
	-e BASE_URL="${BASE_URL}" \
	-e SALE_ID="${SALE_ID}" \
	-e BUYER_POOL="${BUYER_POOL}" \
	-e SCENARIO="${SCENARIO}" \
	-e VUS="${VUS:-200}" \
	-e ITERATIONS="${ITERATIONS:-1000000}" \
	--summary-export "${OUT}" \
	"${ROOT}/load-test/claim.js"

echo "k6 summary: ${OUT}"
echo "ledger snapshot:"
curl -sf "${BASE_URL}/api/v1/admin/sales/${SALE_ID}/stats" | python3 -m json.tool
