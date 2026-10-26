import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { LAB_ORIGIN, ORDER_URL, ACCOUNT_URL, assertLabTarget, parseArgs, requestGet, runStage, summarize, validOrder } from './capacity-lab.mjs';

const AUTHORIZATION = 'Basic dGVzdDpub3QtYS1yZWFsLXNlY3JldA==';
const FIXTURE = Object.freeze({ shopId: 100, orderNo: 'LAB-000001', status: 'SHIPPED', statusDescription: 'Shipped',
  totalAmount: 199.90, currency: 'CNY', createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z', receivedAt: null });
const jsonResponse = (value = FIXTURE, status = 200, marked = true) => new Response(JSON.stringify(value), {
  status, headers: { 'content-type': 'application/json', ...(marked ? { 'x-supportflow-environment': 'isolated-lab' } : {}) },
});

test('only the fixed literal isolated origin is permitted', () => {
  assert.equal(assertLabTarget(LAB_ORIGIN), LAB_ORIGIN);
  for (const target of ['http://127.0.0.1:8080', 'http://127.0.0.1:5173', 'http://localhost:18090',
    'http://127.0.0.1:18090/', 'http://example.com:18090', 'http://127.0.0.1:18090@evil.example', 'https://127.0.0.1:18090']) {
    assert.throws(() => assertLabTarget(target), { message: 'UNSAFE_TARGET' });
  }
});

test('CLI bounds reject destination overrides and unbounded or duplicated stages', () => {
  assert.deepEqual(parseArgs([]).concurrency, [1, 4, 8]);
  assert.equal(parseArgs(['--seconds=30', '--max-requests=1000', '--concurrency=1,16']).seconds, 30);
  for (const argument of ['--target=http://127.0.0.1:8080', '--password-file=other', '--output=other',
    '--seconds=61', '--seconds=0', '--max-requests=1001', '--max-requests=0', '--concurrency=17',
    '--concurrency=4,1', '--concurrency=1,1', '--concurrency=1,2,4,8,16']) {
    assert.throws(() => parseArgs([argument]), { message: 'INVALID_ARGUMENTS' });
  }
  assert.throws(() => parseArgs(['--seconds=10', '--seconds=30']), { message: 'INVALID_ARGUMENTS' });
});

test('statistics partition attempts and only validated 200s contribute latency percentiles', () => {
  const samples = Array.from({ length: 100 }, (_, index) => ({ kind: 'ok', status: 200, elapsedMs: index + 1 }));
  samples.push({ kind: 'timeout', status: 200, elapsedMs: 5000 }, { kind: 'httpError', status: 503, elapsedMs: 3 },
    { kind: 'networkError', status: 0, elapsedMs: 4 }, { kind: 'invalidResponse', status: 200, elapsedMs: 2 },
    { kind: 'safetyError', status: 200, elapsedMs: 1 }, { kind: 'aborted', status: 0, elapsedMs: 1 });
  const result = summarize(samples, 2000);
  assert.equal(result.attempt, 106);
  assert.equal(result.ok, 100);
  assert.deepEqual([result.p50Ms, result.p95Ms, result.p99Ms], [50, 95, 99]);
  assert.equal(result.latencySampleCount, 100);
  assert.equal(result.successfulRps, 50);
  assert.equal(result.actualRps, 52.5);
  assert.equal(result.httpErrors + result.timeouts + result.networkErrors + result.invalidResponses + result.safetyErrors + result.aborted + result.ok, result.attempt);
  assert.deepEqual(result.responseCorrectness, { validated: 101, passed: 100, failed: 1 });
  assert.equal(summarize([{ kind: 'timeout', status: 0, elapsedMs: 5000 }], 5000).p99Ms, null);
});

test('public response validation requires fixture identity and never accepts internal identifiers', () => {
  assert.equal(validOrder(FIXTURE), true);
  for (const changed of [{ shopId: 101 }, { orderNo: 'SF10001' }, { buyerId: 200 }, { totalAmount: -1 }, { currency: 'USD' }, { createdAt: 'invalid' }]) {
    assert.equal(validOrder({ ...FIXTURE, ...changed }), false);
  }
});

test('each attempt uses only GET, disables redirects and validates environment plus body', async () => {
  let calls = 0;
  const result = await requestGet({ authorization: AUTHORIZATION, fetchImpl: async (url, options) => {
    calls++;
    assert.equal(url, ORDER_URL);
    assert.equal(options.method, 'GET');
    assert.equal(options.redirect, 'manual');
    return jsonResponse();
  } });
  assert.equal(result.kind, 'ok');
  assert.equal(calls, 1);
  assert.equal((await requestGet({ authorization: AUTHORIZATION, fetchImpl: async () => jsonResponse(FIXTURE, 200, false) })).kind, 'safetyError');
  assert.equal((await requestGet({ authorization: AUTHORIZATION, fetchImpl: async () => jsonResponse({ ...FIXTURE, orderNo: 'OTHER' }) })).kind, 'invalidResponse');
  assert.equal((await requestGet({ authorization: AUTHORIZATION, fetchImpl: async () => jsonResponse(FIXTURE, 503) })).kind, 'httpError');
  await assert.rejects(requestGet({ url: `${LAB_ORIGIN}/api/v1/chat/messages`, authorization: AUTHORIZATION }), { message: 'UNSAFE_ROUTE' });
});

test('preflight identity cannot accept daily or merchant accounts', async () => {
  for (const account of [{ username: 'customer-200', accountType: 'BUYER' },
    { username: 'lab-buyer', accountType: 'MERCHANT_STAFF' },
    { username: 'lab-buyer', accountType: 'BUYER', capabilities: { manageKnowledge: true } }]) {
    assert.equal((await requestGet({ url: ACCOUNT_URL, authorization: AUTHORIZATION,
      fetchImpl: async () => jsonResponse(account) })).kind, 'invalidResponse');
  }
  assert.equal((await requestGet({ url: ACCOUNT_URL, authorization: AUTHORIZATION,
    fetchImpl: async () => jsonResponse({ username: 'lab-buyer', accountType: 'BUYER' }) })).kind, 'ok');
});

test('provider or transport error details are discarded and requests are not retried', async () => {
  const secretMarker = 'test-sensitive-data-not-to-be-reported';
  let calls = 0;
  const result = await requestGet({ authorization: AUTHORIZATION, fetchImpl: async () => {
    calls++;
    throw new Error(secretMarker);
  } });
  assert.equal(result.kind, 'networkError');
  assert.equal(calls, 1);
  assert.equal(JSON.stringify(result).includes(secretMarker), false);
});

test('malformed or oversized JSON fails correctness without returning its contents', async () => {
  const marker = 'private-response-marker';
  for (const body of [`{${marker}`, JSON.stringify({ content: marker.repeat(4000) })]) {
    const result = await requestGet({ authorization: AUTHORIZATION, fetchImpl: async () => new Response(body, {
      status: 200, headers: { 'content-type': 'application/json', 'x-supportflow-environment': 'isolated-lab' },
    }) });
    assert.equal(result.kind, 'invalidResponse');
    assert.equal(JSON.stringify(result).includes(marker), false);
  }
});

test('an already-stopped attempt never calls transport', async () => {
  const stop = new AbortController();
  stop.abort();
  const result = await requestGet({ authorization: AUTHORIZATION, signal: stop.signal,
    fetchImpl: async () => assert.fail('Stopped requests must never call transport') });
  assert.equal(result.kind, 'aborted');
});

test('request deadline aborts a real local body stalled after HTTP 200 headers', async () => {
  await withServer((request, response) => {
    response.writeHead(200, { 'content-type': 'application/json', 'x-supportflow-environment': 'isolated-lab' });
    response.write('{');
  }, async origin => {
    const result = await requestGet({ authorization: AUTHORIZATION, timeoutMs: 80,
      fetchImpl: (_fixedUrl, options) => fetch(origin, options) });
    assert.equal(result.kind, 'timeout');
    assert.equal(result.status, 200);
    assert.equal(summarize([result], result.elapsedMs).latencySampleCount, 0);
  });
});

test('real local JSON response validates without ever changing the production CLI target', async () => {
  await withServer((request, response) => {
    assert.equal(request.method, 'GET');
    response.writeHead(200, { 'content-type': 'application/json', 'x-supportflow-environment': 'isolated-lab' });
    response.end(JSON.stringify(FIXTURE));
  }, async origin => {
    assert.equal((await requestGet({ authorization: AUTHORIZATION,
      fetchImpl: (_fixedUrl, options) => fetch(origin, options) })).kind, 'ok');
    assert.equal(LAB_ORIGIN, 'http://127.0.0.1:18090');
  });
});

test('stage reservations cannot exceed the request cap or concurrency bound', async () => {
  let active = 0;
  let maximum = 0;
  let calls = 0;
  const result = await runStage({ concurrency: 8, durationMs: 1000, maxRequests: 37, request: async () => {
    calls++;
    maximum = Math.max(maximum, ++active);
    await new Promise(resolve => setImmediate(resolve));
    active--;
    return { kind: 'ok', status: 200, elapsedMs: 1 };
  } });
  assert.equal(calls, 37);
  assert.equal(maximum, 8);
  assert.equal(result.stopReason, 'REQUEST_LIMIT');
  assert.equal(result.attempt, 37);
});

test('stop aborts all in-flight requests without admitting replacements', async () => {
  const stop = new AbortController();
  let admitted = 0;
  let aborted = 0;
  let allReady;
  const ready = new Promise(resolve => { allReady = resolve; });
  const pending = runStage({ concurrency: 4, durationMs: 5000, maxRequests: 1000, signal: stop.signal,
    request: async ({ signal }) => new Promise(resolve => {
      admitted++;
      signal.addEventListener('abort', () => { aborted++; resolve({ kind: 'aborted', status: 0, elapsedMs: 1 }); }, { once: true });
      if (admitted === 4) allReady();
    }) });
  await ready;
  stop.abort();
  const result = await pending;
  assert.equal(result.stopReason, 'STOPPED');
  assert.equal(admitted, 4);
  assert.equal(aborted, 4);
  assert.equal(result.aborted, 4);
  assert.equal(result.latencySampleCount, 0);
});

test('stage deadline cancels in-flight attempts, instead of silently running beyond its duration', async () => {
  const result = await runStage({ concurrency: 2, durationMs: 30, maxRequests: 1000,
    request: async ({ signal }) => new Promise(resolve => signal.addEventListener('abort', () =>
      resolve({ kind: 'aborted', status: 0, elapsedMs: 30 }), { once: true })) });
  assert.equal(result.stopReason, 'DURATION_LIMIT');
  assert.equal(result.attempt, 2);
  assert.equal(result.aborted, 2);
});

test('environment mismatch stops the stage immediately and preserves correctness failures', async () => {
  let calls = 0;
  const result = await runStage({ concurrency: 1, durationMs: 1000, maxRequests: 1000,
    request: async () => { calls++; return { kind: 'invalidResponse', status: 200, elapsedMs: 1 }; } });
  assert.equal(calls, 1);
  assert.equal(result.stopReason, 'SAFETY_CHECK_FAILED');
  assert.equal(result.invalidResponses, 1);
  assert.equal(result.ok, 0);
  assert.equal(result.p50Ms, null);
});

test('invalid stage settings fail before a request can start', async () => {
  for (const changed of [{ concurrency: 17 }, { durationMs: 60_001 }, { maxRequests: 1001 }, { maxRequests: 0 }]) {
    await assert.rejects(runStage({ concurrency: 1, durationMs: 1000, maxRequests: 10,
      request: async () => assert.fail('Unsafe stages must never call transport'), ...changed }), { message: 'INVALID_STAGE' });
  }
});

test('an already-stopped stage admits no requests and keeps empty latency statistics', async () => {
  const stop = new AbortController();
  stop.abort();
  const result = await runStage({ concurrency: 8, durationMs: 1000, maxRequests: 1000, signal: stop.signal,
    request: async () => assert.fail('Stopped stages must never call transport') });
  assert.equal(result.stopReason, 'STOPPED');
  assert.equal(result.attempt, 0);
  assert.equal(result.p95Ms, null);
});

async function withServer(handler, run) {
  const server = http.createServer(handler);
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  try { await run(`http://127.0.0.1:${server.address().port}`); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}
