#!/usr/bin/env bash
# Same k6 script and sale shape as the baseline, against flashsale.gate=redis.
# Start the app with:  ./mvnw spring-boot:run -Dspring-boot.run.arguments=--flashsale.gate=redis
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export LABEL="${LABEL:-optimized}"
exec "${ROOT}/load-test/run-baseline.sh"
