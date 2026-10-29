#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { lstat, mkdir, open, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { createSessionClient } from './session-client.mjs';

export const LAB_ORIGIN = 'http://127.0.0.1:18090';
export const ORDER_URL = `${LAB_ORIGIN}/api/v1/shops/100/orders/LAB-000001`;
export const ACCOUNT_URL = `${LAB_ORIGIN}/api/v1/security/me`;
const ROOT = fileURLToPath(new URL('../', import.meta.url));
const SECRET_FILE = path.join(ROOT, '.local', 'deployment-lab', 'lab_buyer_password');
const RESULTS_DIR = path.join(ROOT, '.local', 'capacity-results');
const MARKER_HEADER = 'x-supportflow-environment';
const MARKER = 'isolated-lab';
const MAX_BODY_BYTES = 32 * 1024;
const KINDS = new Set(['ok', 'httpError', 'timeout', 'networkError', 'invalidResponse', 'safetyError', 'aborted']);
const CONTAINERS = Object.freeze([
  'supportflow-lab-backend-1', 'supportflow-lab-mysql-1', 'supportflow-lab-frontend-1',
]);

class LabError extends Error {
  constructor(code) { super(code); this.code = code; }
}

/** No target, origin, password-file or output override is accepted by the executable. */
export function parseArgs(args) {
  const options = { seconds: 10, concurrency: [1, 4, 8], maxRequests: 1000, dockerStats: false, help: false };
  const seen = new Set();
  for (const argument of args) {
    const [name, value, extra] = argument.split('=');
    if (extra !== undefined || seen.has(name)) throw new LabError('INVALID_ARGUMENTS');
    seen.add(name);
    if (name === '--help' && value === undefined) options.help = true;
    else if (name === '--docker-stats' && value === undefined) options.dockerStats = true;
    else if (name === '--seconds' && /^(10|30)$/.test(value ?? '')) options.seconds = Number(value);
    else if (name === '--max-requests' && /^\d+$/.test(value ?? '')
      && Number(value) >= 1 && Number(value) <= 1000) options.maxRequests = Number(value);
    else if (name === '--concurrency' && /^(?:[1-9]|1[0-6])(?:,(?:[1-9]|1[0-6])){0,3}$/.test(value ?? '')) {
      const stages = value.split(',').map(Number);
      if (stages.some((number, index) => index > 0 && number <= stages[index - 1])) throw new LabError('INVALID_ARGUMENTS');
      options.concurrency = stages;
    } else throw new LabError('INVALID_ARGUMENTS');
  }
  return options;
}

export function assertLabTarget(origin) {
  if (origin !== LAB_ORIGIN) throw new LabError('UNSAFE_TARGET');
  return origin;
}

function assertReadUrl(url) {
  if (url !== ORDER_URL && url !== ACCOUNT_URL) throw new LabError('UNSAFE_ROUTE');
  assertLabTarget(new URL(url).origin);
}

export function validOrder(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    && value.shopId === 100 && value.orderNo === 'LAB-000001'
    && value.status === 'SHIPPED'
    && typeof value.statusDescription === 'string' && value.statusDescription.length > 0
    && value.totalAmount === 199.90
    && value.currency === 'CNY'
    && ['createdAt', 'updatedAt'].every(key => typeof value[key] === 'string' && Number.isFinite(Date.parse(value[key])))
    && (value.receivedAt === null || typeof value.receivedAt === 'string' && Number.isFinite(Date.parse(value.receivedAt)))
    && !['id', 'buyerId', 'customerId', 'tenantId', 'orderId'].some(key => Object.hasOwn(value, key));
}

function validAccount(value) {
  return value !== null && typeof value === 'object' && value.username === 'lab-buyer'
    && value.accountType === 'BUYER'
    && !value.capabilities?.reviewAfterSales && !value.capabilities?.manageKnowledge;
}

async function readJson(response) {
  if (!response.body || !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) {
    throw new LabError('INVALID_RESPONSE');
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    if (size > MAX_BODY_BYTES) throw new LabError('INVALID_RESPONSE');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } catch { throw new LabError('INVALID_RESPONSE'); }
}

/** One GET attempt, including body and correctness checks. No response body or exception text escapes. */
export async function requestGet({ url = ORDER_URL, signal, timeoutMs = 5000, fetchImpl = fetch }) {
  assertReadUrl(url);
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 5000) throw new LabError('INVALID_TIMEOUT');
  const started = performance.now();
  const timeout = new AbortController();
  const combined = signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal;
  const timer = setTimeout(() => timeout.abort(), timeoutMs);
  let status = 0;
  let response;
  let kind;
  try {
    if (combined.aborted) throw new LabError('ABORTED');
    response = await fetchImpl(url, {
      method: 'GET', redirect: 'manual', cache: 'no-store', signal: combined,
      headers: { accept: 'application/json' },
    });
    status = Number.isInteger(response.status) ? response.status : 0;
    if (response.headers.get(MARKER_HEADER) !== MARKER) kind = 'safetyError';
    else if (status !== 200) kind = 'httpError';
    else {
      const value = await readJson(response);
      kind = (url === ACCOUNT_URL ? validAccount(value) : validOrder(value)) ? 'ok' : 'invalidResponse';
    }
  } catch (failure) {
    kind = signal?.aborted ? 'aborted' : timeout.signal.aborted ? 'timeout'
      : failure?.message === 'SESSION_ENVIRONMENT_INVALID' ? 'safetyError'
      : failure instanceof LabError && failure.code === 'INVALID_RESPONSE' ? 'invalidResponse' : 'networkError';
  } finally {
    clearTimeout(timer);
    // Abort/cancel even malformed or refused bodies, so validation cannot leave connections running.
    if (kind !== 'ok') {
      timeout.abort();
      try { await response?.body?.cancel(); } catch { /* No raw transport details. */ }
    }
  }
  return { kind, status, elapsedMs: Math.max(0, performance.now() - started) };
}

function percentile(sorted, fraction) {
  return sorted.length === 0 ? null : sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

export function summarize(samples, elapsedMs) {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0) throw new LabError('INVALID_ELAPSED_TIME');
  const counts = { attempt: samples.length, ok: 0, httpErrors: 0, timeouts: 0, networkErrors: 0,
    invalidResponses: 0, safetyErrors: 0, aborted: 0 };
  const keys = { ok: 'ok', httpError: 'httpErrors', timeout: 'timeouts', networkError: 'networkErrors',
    invalidResponse: 'invalidResponses', safetyError: 'safetyErrors', aborted: 'aborted' };
  const statuses = {};
  const successful = [];
  for (const sample of samples) {
    if (!KINDS.has(sample.kind) || !Number.isFinite(sample.elapsedMs) || sample.elapsedMs < 0) throw new LabError('INVALID_SAMPLE');
    counts[keys[sample.kind]]++;
    if (sample.status >= 100 && sample.status <= 599) statuses[sample.status] = (statuses[sample.status] ?? 0) + 1;
    if (sample.kind === 'ok') successful.push(sample.elapsedMs);
  }
  successful.sort((left, right) => left - right);
  const seconds = elapsedMs / 1000;
  return { ...counts, elapsedMs, httpStatuses: statuses,
    latencyPopulation: 'validated_http_200_only', latencySampleCount: successful.length,
    p50Ms: percentile(successful, 0.50), p95Ms: percentile(successful, 0.95), p99Ms: percentile(successful, 0.99),
    attemptRps: seconds > 0 ? counts.attempt / seconds : 0,
    actualRps: seconds > 0 ? (counts.attempt - counts.aborted) / seconds : 0,
    successfulRps: seconds > 0 ? counts.ok / seconds : 0,
    responseCorrectness: { validated: counts.ok + counts.invalidResponses, passed: counts.ok, failed: counts.invalidResponses },
  };
}

/** Finite closed-loop workers; stage/stop cancellation reaches every in-flight attempt. */
export async function runStage({ concurrency, durationMs, maxRequests = 1000, request, signal }) {
  if (!Number.isInteger(concurrency) || concurrency < 1 || concurrency > 16
    || !Number.isInteger(durationMs) || durationMs < 1 || durationMs > 60_000
    || !Number.isInteger(maxRequests) || maxRequests < 1 || maxRequests > 1000
    || typeof request !== 'function') throw new LabError('INVALID_STAGE');
  const started = performance.now();
  const controller = new AbortController();
  const combined = signal ? AbortSignal.any([signal, controller.signal]) : controller.signal;
  const samples = [];
  let reserved = 0;
  let stopReason;
  const timer = setTimeout(() => { stopReason ??= 'DURATION_LIMIT'; controller.abort(); }, durationMs);
  try {
    await Promise.all(Array.from({ length: concurrency }, async () => {
      while (!combined.aborted && reserved < maxRequests) {
        if (performance.now() - started >= durationMs) { stopReason ??= 'DURATION_LIMIT'; controller.abort(); break; }
        reserved++;
        let outcome;
        const attemptStarted = performance.now();
        try { outcome = await request({ signal: combined }); }
        catch { outcome = { kind: combined.aborted ? 'aborted' : 'networkError', status: 0, elapsedMs: performance.now() - attemptStarted }; }
        samples.push(outcome);
        if (outcome.kind === 'safetyError' || outcome.kind === 'invalidResponse') {
          stopReason = 'SAFETY_CHECK_FAILED';
          controller.abort();
        }
      }
    }));
  } finally { clearTimeout(timer); controller.abort(); }
  stopReason ??= signal?.aborted ? 'STOPPED' : reserved >= maxRequests ? 'REQUEST_LIMIT' : 'DURATION_LIMIT';
  if (signal?.aborted && stopReason !== 'SAFETY_CHECK_FAILED') stopReason = 'STOPPED';
  return { concurrency, configuredDurationMs: durationMs, maxRequests, stopReason,
    ...summarize(samples, performance.now() - started) };
}

/** Optional local observation; fixed argv only, no shell, no arbitrary container or Docker command. */
export function sampleDockerStats(signal) {
  return new Promise(resolve => {
    if (signal?.aborted) return resolve({ status: 'skipped_stopped' });
    execFile('docker', ['stats', '--no-stream', '--format', '{{json .}}', ...CONTAINERS],
      { shell: false, windowsHide: true, timeout: 5000, maxBuffer: 64 * 1024, encoding: 'utf8', signal }, (error, stdout) => {
        if (error) return resolve({ status: 'unavailable' });
        try {
          const containers = stdout.trim().split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line)).map(row => {
            if (!CONTAINERS.includes(row.Name)) throw new LabError('UNEXPECTED_CONTAINER');
            return Object.fromEntries(['Name', 'CPUPerc', 'MemUsage', 'MemPerc', 'NetIO', 'BlockIO', 'PIDs']
              .map(key => [key, typeof row[key] === 'string' ? row[key].slice(0, 160) : null]));
          });
          resolve({ status: 'sampled', containers });
        } catch { resolve({ status: 'unavailable' }); }
      });
  });
}

async function readLabPassword() {
  try {
    const metadata = await lstat(SECRET_FILE);
    if (!metadata.isFile() || metadata.isSymbolicLink() || metadata.size < 1 || metadata.size > 1024) throw new LabError('LAB_SECRET_UNAVAILABLE');
    const password = (await readFile(SECRET_FILE, 'utf8')).replace(/\r?\n$/, '');
    if (!password || /[\r\n\0]/.test(password)) throw new LabError('LAB_SECRET_UNAVAILABLE');
    return password;
  } catch { throw new LabError('LAB_SECRET_UNAVAILABLE'); }
}

async function saveReport(report) {
  await mkdir(RESULTS_DIR, { recursive: true, mode: 0o700 });
  const directory = await lstat(RESULTS_DIR);
  if (!directory.isDirectory() || directory.isSymbolicLink()) throw new LabError('UNSAFE_RESULTS_DIRECTORY');
  const name = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID()}.json`;
  const destination = path.join(RESULTS_DIR, name);
  const file = await open(destination, 'wx', 0o600);
  try { await file.writeFile(`${JSON.stringify(report, null, 2)}\n`, 'utf8'); await file.sync(); }
  finally { await file.close(); }
  return destination;
}

async function runBenchmark(options, session, signal) {
  const fetchImpl = (url, init) => session.fetch(new URL(url).pathname, init);
  const report = {
    schemaVersion: 1, purpose: 'local_isolated_learning_measurement_not_production_capacity',
    target: LAB_ORIGIN, route: '/api/v1/shops/100/orders/LAB-000001', methods: ['GET'], automaticRetries: 0,
    authentication: { mechanism: 'server_cookie_session', loginBeforeMeasurement: true },
    startedAt: new Date().toISOString(), configuration: { ...options, timeoutMs: 5000, warmupMaxRequests: 20, warmupDurationMs: 3000 },
    machine: { platform: os.platform(), release: os.release(), arch: os.arch(), node: process.version,
      cpuModel: os.cpus()[0]?.model ?? 'unknown', logicalCpus: os.cpus().length, totalMemoryBytes: os.totalmem() },
    verification: { markerRequired: `${MARKER_HEADER}: ${MARKER}`, account: 'lab-buyer', shopId: 100,
      orderNo: 'LAB-000001', orderStatus: 'SHIPPED', orderAmount: 199.90, currency: 'CNY' },
    faultValidation: { mode: 'separate_automated_mock_tests', command: 'node --test scripts/capacity-lab.test.mjs',
      executedByBenchmark: false, realModelRequests: 0 },
    stages: [], status: 'RUNNING',
  };
  for (const url of [ACCOUNT_URL, ORDER_URL]) {
    const checked = await requestGet({ url, fetchImpl, signal });
    if (checked.kind !== 'ok') {
      report.status = signal.aborted ? 'STOPPED' : 'REFUSED';
      report.refusalCode = 'LAB_PREFLIGHT_FAILED';
      report.preflightFailure = { kind: checked.kind, status: checked.status };
      report.finishedAt = new Date().toISOString();
      return report;
    }
  }
  report.preflight = 'PASSED';
  const request = ({ signal: activeSignal }) => requestGet({ fetchImpl, signal: activeSignal });
  report.warmup = await runStage({ concurrency: 1, durationMs: 3000, maxRequests: 20, request, signal });
  if (report.warmup.stopReason !== 'SAFETY_CHECK_FAILED' && !signal.aborted) {
    for (const concurrency of options.concurrency) {
      if (signal.aborted) break;
      const before = options.dockerStats ? await sampleDockerStats(signal) : undefined;
      if (signal.aborted) break;
      const stage = await runStage({ concurrency, durationMs: options.seconds * 1000,
        maxRequests: options.maxRequests, request, signal });
      if (options.dockerStats) stage.docker = { before, after: await sampleDockerStats(signal) };
      report.stages.push(stage);
      if (stage.stopReason === 'SAFETY_CHECK_FAILED') break;
    }
  }
  report.status = signal.aborted ? 'STOPPED'
    : report.warmup.stopReason === 'SAFETY_CHECK_FAILED' || report.stages.some(stage => stage.stopReason === 'SAFETY_CHECK_FAILED')
      ? 'REFUSED' : 'COMPLETED';
  report.finishedAt = new Date().toISOString();
  return report;
}

export async function main(args = process.argv.slice(2)) {
  const options = parseArgs(args);
  if (options.help) {
    console.log('Usage: node scripts/capacity-lab.mjs [--seconds=10|30] [--concurrency=1,4,8] [--max-requests=1000] [--docker-stats]');
    console.log(`Fixed isolated target: ${LAB_ORIGIN}; measured GET only after session login; no model calls; local learning results only.`);
    return;
  }
  assertLabTarget(LAB_ORIGIN);
  const password = await readLabPassword();
  const session = createSessionClient(LAB_ORIGIN, { cookieName: 'SF_LAB_SESSION', marker: 'isolated-lab' });
  const stop = new AbortController();
  const onStop = () => stop.abort();
  process.once('SIGINT', onStop);
  process.once('SIGTERM', onStop);
  try {
    await session.login('lab-buyer', password);
    const report = await runBenchmark(options, session, stop.signal);
    const destination = await saveReport(report);
    console.log(`CAPACITY_STATUS=${report.status}`);
    console.log(`CAPACITY_REPORT=${destination}`);
    if (report.status !== 'COMPLETED') process.exitCode = report.status === 'STOPPED' ? 130 : 1;
  } finally {
    try { if (session.hasSessionCookie()) await session.logout(); } catch { /* A failed cleanup cannot expose credentials or trigger a request replay. */ }
    process.removeListener('SIGINT', onStop); process.removeListener('SIGTERM', onStop);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(failure => {
    console.error(`CAPACITY_REFUSED=${failure instanceof LabError ? failure.code : 'LOCAL_LAB_FAILURE'}`);
    process.exitCode = 1;
  });
}
