# Load tests

k6 scripts that drive the claim endpoint with 1,000,000 total purchase attempts.

"1M" is total iterations, not concurrent connections: a laptop cannot hold a
million sockets, so the runs use a bounded pool of virtual users (about 100 to 200)
with HTTP keep-alive and `shared-iterations` executor.

Each run is executed twice on the same sale shape (stock ~1,000, 1 unit per user):

1. `baseline` - conditional SQL decrement, order written in the request thread
2. `optimized` - Redis Lua gate + transactional outbox + Kafka order worker

Recorded per run in `results/`: machine specs, VUs, achieved RPS, duration,
p50 / p95 / p99 latency, accepted vs sold-out vs duplicate responses, and the
oversell count (must be 0).

Install k6: `brew install k6`
