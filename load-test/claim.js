import http from 'k6/http';
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';

/**
 * Drives POST /api/v1/sales/{saleId}/claims.
 *
 * "1M" is total attempts (shared-iterations), not a million sockets. Buyer
 * identity is a pre-signed HS256 JWT (subject lt-N). Each iteration uses a
 * fresh Idempotency-Key so a repeat visit is a duplicate-claim, not a replay.
 */
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const SALE_ID = __ENV.SALE_ID;
const BUYER_POOL = Number(__ENV.BUYER_POOL || 50000);
const SCENARIO = __ENV.SCENARIO || 'full';
const JWT_SECRET = __ENV.JWT_SECRET || 'local-dev-only-hs256-secret-32b!!';

const accepted = new Counter('claims_accepted');
const soldOut = new Counter('claims_sold_out');
const duplicate = new Counter('claims_duplicate');
const replayed = new Counter('claims_replayed');
const rateLimited = new Counter('claims_rate_limited');
const other = new Counter('claims_other');

export const options = optionsFor(SCENARIO);

function optionsFor(name) {
	const common = { summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'] };
	if (name === 'smoke') {
		return { ...common, scenarios: { smoke: shared(10, 200, '2m') } };
	}
	if (name === 'warmup') {
		return { ...common, scenarios: { warmup: shared(50, 10000, '10m') } };
	}
	return {
		...common,
		scenarios: {
			spike: shared(Number(__ENV.VUS || 200), Number(__ENV.ITERATIONS || 1000000), __ENV.MAX_DURATION || '2h'),
		},
	};
}

function shared(vus, iterations, maxDuration) {
	return { executor: 'shared-iterations', vus, iterations, maxDuration };
}

function signJwt(sub, role) {
	const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
	const now = Math.floor(Date.now() / 1000);
	const payload = encoding.b64encode(JSON.stringify({ sub, role, iat: now, exp: now + 86400 }), 'rawurl');
	const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
	return `${header}.${payload}.${sig}`;
}

export function setup() {
	if (!SALE_ID) {
		throw new Error('SALE_ID is required (seed a sale first: load-test/seed-sale.sh)');
	}
	const health = http.get(`${BASE_URL}/actuator/health`);
	if (health.status !== 200) {
		throw new Error(`app is not healthy at ${BASE_URL}: HTTP ${health.status}`);
	}
	const tokens = [];
	for (let i = 0; i < BUYER_POOL; i++) {
		tokens.push(signJwt(`lt-${i}`, 'BUYER'));
	}
	return { saleId: SALE_ID, tokens };
}

export default function (data) {
	const buyerIndex = ((__VU - 1) + (__ITER * 1024)) % BUYER_POOL;
	const key = `k-${__VU}-${__ITER}-${Date.now()}`;

	const res = http.post(
		`${BASE_URL}/api/v1/sales/${data.saleId}/claims`,
		JSON.stringify({ quantity: 1, clientTimestamp: new Date().toISOString() }),
		{
			headers: {
				'Content-Type': 'application/json',
				Authorization: `Bearer ${data.tokens[buyerIndex]}`,
				'Idempotency-Key': key,
			},
			tags: { name: 'claim' },
		},
	);

	if (res.status === 202) {
		accepted.add(1);
	} else if (res.status === 200) {
		replayed.add(1);
	} else if (res.status === 429) {
		rateLimited.add(1);
	} else if (res.status === 409) {
		const type = String(res.json('type') || '');
		if (type.endsWith('/sold-out')) {
			soldOut.add(1);
		} else if (type.endsWith('/duplicate-claim')) {
			duplicate.add(1);
		} else {
			other.add(1);
		}
	} else {
		other.add(1);
	}
}
