# Load tests

k6 scripts that drive `POST /api/v1/sales/{saleId}/claims`. "1M" is total
iterations, not concurrent connections: a laptop cannot hold a million sockets,
so the runs use a bounded pool of virtual users (default 200) with HTTP
keep-alive and the `shared-iterations` executor.

Install k6: `brew install k6` (or drop the official macOS binary on `PATH`).

## Scenarios

| `SCENARIO` | VUs | Iterations | Purpose |
|---|---|---|---|
| `smoke` | 10 | 200 | Script and wiring check |
| `warmup` | 50 | 10,000 | JIT / connection pool; excluded from published figures |
| `full` (default) | 200 | 1,000,000 | The graded before/after run |

Each full run uses the same sale shape: stock 1,000, one unit per buyer, a
pre-seeded pool of 50,000 buyers (`lt-0` … `lt-49999`). Winners who come back
get `409 duplicate-claim`; everyone else after stock is gone gets `409 sold-out`.

Run twice on that shape:

1. `baseline` — `flashsale.gate=sql`
2. `optimized` — `flashsale.gate=redis` (Day 4+)

## How to run

```bash
# 1. start the stack + app (SQL gate is the default)
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"
./mvnw spring-boot:run

# 2. in another shell, create the sale and buyer pool
./load-test/seed-sale.sh

# 3. smoke, then the million-attempt run
source load-test/results/sale.env
k6 run -e SCENARIO=smoke -e SALE_ID="$SALE_ID" load-test/claim.js
k6 run -e SCENARIO=full  -e SALE_ID="$SALE_ID" \
      --summary-export load-test/results/baseline-summary.json \
      load-test/claim.js
```

Or use the wrapper, which seeds, warms up on a throwaway sale, then runs the
measured sale:

```bash
./load-test/run-baseline.sh
```

Recorded per run in `results/`: machine specs, VUs, achieved RPS, duration,
p50 / p95 / p99, accepted vs sold-out vs duplicate, and the oversell count
(must be 0). Raw k6 JSON dumps are git-ignored; the markdown summary is committed.
