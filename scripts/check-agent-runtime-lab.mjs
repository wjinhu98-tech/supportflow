#!/usr/bin/env node
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { isDeepStrictEqual } from 'node:util';

const ORIGIN = 'http://127.0.0.1:18090';
const MARKER = 'isolated-lab';
const CONVERSATIONS = '/api/v1/shops/100/chat/conversations';
const ORDER = 'LAB-000001';
const MESSAGE = '查物流 LAB-000001';
const CONFLICTING_MESSAGE = '查物流 LAB-000001，请再查一次';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SECRET_FILE = new URL('../.local/deployment-lab/lab_buyer_password', import.meta.url);
const MAX_BODY_BYTES = 256 * 1024;
let currentCheck = 'ARL00';
let passed = 0;
let authorization;
let sessionCookie;
let conversationId;
let clientMessageId;

function ensure(condition) {
  if (!condition) throw new Error('AGENT_RUNTIME_LAB_CHECK_FAILED');
}

function pass(id, condition) {
  currentCheck = id;
  ensure(condition);
  passed++;
  process.stdout.write(`${id}=PASS\n`);
}

function runPath(shop = 100) {
  ensure(UUID_PATTERN.test(conversationId ?? '') && UUID_PATTERN.test(clientMessageId ?? ''));
  ensure(shop === 100 || shop === 101);
  return `/api/v1/shops/${shop}/chat/conversations/${conversationId}/runs/${clientMessageId}`;
}

/** Fixed lab routes only; business mutations and arbitrary targets are never accepted. */
function permitted(method, endpoint, body) {
  if (method === 'GET' && ['/', '/api/v1/security/me', '/api/v1/security/csrf'].includes(endpoint)) {
    return body === undefined;
  }
  if (method === 'POST' && endpoint === CONVERSATIONS) return body === undefined && !conversationId;
  if (!UUID_PATTERN.test(conversationId ?? '') || !UUID_PATTERN.test(clientMessageId ?? '')) return false;
  const messages = `${CONVERSATIONS}/${conversationId}/messages`;
  if (method === 'GET' && endpoint === messages) return body === undefined;
  if (method === 'POST' && endpoint === messages) {
    return body !== null && typeof body === 'object' && Object.keys(body).length === 3
      && body.clientMessageId === clientMessageId && body.orderNo === ORDER
      && [MESSAGE, CONFLICTING_MESSAGE].includes(body.message);
  }
  for (const shop of [100, 101]) {
    if (method === 'GET' && endpoint === runPath(shop)) return body === undefined;
    if (method === 'POST' && endpoint === `${runPath(shop)}/resume`) return body === undefined;
  }
  return false;
}

function rememberSession(response) {
  for (const value of response.headers.getSetCookie()) {
    const pair = value.split(';', 1)[0];
    if (pair.startsWith('SF_LAB_SESSION=')) {
      ensure(/^SF_LAB_SESSION=[A-Za-z0-9._-]+$/.test(pair));
      sessionCookie = pair;
    }
  }
}

async function call(method, endpoint, { body, expected = 200, csrf } = {}) {
  ensure(permitted(method, endpoint, body));
  const url = new URL(endpoint, ORIGIN);
  ensure(url.origin === ORIGIN && url.pathname === endpoint && !url.search && !url.hash);
  const headers = { Accept: endpoint === '/' ? 'text/html' : 'application/json' };
  if (authorization) headers.Authorization = authorization;
  if (sessionCookie) headers.Cookie = sessionCookie;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method === 'POST') {
    ensure(csrf?.headerName === 'X-CSRF-TOKEN' && typeof csrf.token === 'string'
      && csrf.token.length > 0 && csrf.token.length <= 2048 && !/[\r\n]/.test(csrf.token) && sessionCookie);
    headers[csrf.headerName] = csrf.token;
  }
  // A mutation with an uncertain outcome is never automatically retried.
  const response = await fetch(url, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error', signal: AbortSignal.timeout(15_000), cache: 'no-store',
  });
  ensure(response.status === expected && response.headers.get('x-supportflow-environment') === MARKER);
  rememberSession(response);
  return response;
}

async function text(response) {
  ensure(response.body);
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.byteLength;
    ensure(size <= MAX_BODY_BYTES);
    chunks.push(chunk);
  }
  return new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks));
}

async function json(response) {
  ensure(/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? ''));
  return JSON.parse(await text(response));
}

async function post(endpoint, body, expected = 200) {
  const csrf = await json(await call('GET', '/api/v1/security/csrf'));
  return json(await call('POST', endpoint, { body, expected, csrf }));
}

function safeProgress(run) {
  if (run === null || typeof run !== 'object' || !Array.isArray(run.steps)) return false;
  const internal = ['buyerId', 'customerId', 'tenantId', 'orderId', 'conversationId', 'turnId',
    'request', 'requestHash', 'request_hash', 'generation', 'leaseUntil', 'processingToken', 'processing_token'];
  const serialized = JSON.stringify(run);
  return !internal.some(field => serialized.includes(`"${field}":`))
    && !serialized.includes('Basic ') && !serialized.includes('Bearer ');
}

async function main() {
  ensure(process.argv.length === 2);
  // Probe the isolated marker before reading any local credential.
  currentCheck = 'ARL01';
  const page = await call('GET', '/');
  pass('ARL01', (await text(page)).includes('<html'));
  currentCheck = 'ARL02';
  const password = (await readFile(SECRET_FILE, 'utf8')).trim();
  ensure(/^[A-Za-z0-9_-]{24,128}$/.test(password));
  authorization = `Basic ${Buffer.from(`lab-buyer:${password}`).toString('base64')}`;
  const me = await json(await call('GET', '/api/v1/security/me'));
  pass('ARL02', me.username === 'lab-buyer' && me.accountType === 'BUYER');

  currentCheck = 'ARL03';
  const conversation = await post(CONVERSATIONS, undefined, 201);
  ensure(UUID_PATTERN.test(conversation.id ?? ''));
  conversationId = conversation.id;
  clientMessageId = randomUUID();
  pass('ARL03', conversation.shopId === 100);
  const messages = `${CONVERSATIONS}/${conversationId}/messages`;
  const request = { clientMessageId, message: MESSAGE, orderNo: ORDER };
  currentCheck = 'ARL04';
  const completed = await post(messages, request);
  pass('ARL04', completed.conversation?.id === conversationId && completed.messages?.length === 2
    && completed.messages[0].role === 'USER' && completed.messages[0].text === MESSAGE
    && completed.messages[1].role === 'ASSISTANT'
    && typeof completed.messages[1].response?.reply === 'string'
    && completed.messages[1].response.reply.includes(ORDER));

  currentCheck = 'ARL05';
  const run = await json(await call('GET', runPath()));
  pass('ARL05', UUID_PATTERN.test(run.runId ?? '') && run.clientMessageId === clientMessageId
    && run.status === 'SUCCEEDED' && run.phase === 'COMPLETE'
    && run.attempts === 5 && run.resumable === false && Number.isInteger(run.maxSteps) && run.maxSteps >= 5
    && run.workflowVersion === 2 && run.budget?.chargedTokens === 0 && run.budget?.currency === 'USD'
    && run.result === completed.messages[1].response.reply
    && Number.isFinite(Date.parse(run.deadlineAt)));
  pass('ARL06', Array.isArray(run.steps) && run.steps.length === 5
    && run.steps.map(step => step.phase).join(',') === 'PLAN,ORDER_LOOKUP,PLAN,LOGISTICS_LOOKUP,PLAN'
    && run.steps.every((step, index) => step.status === 'SUCCEEDED' && step.attempt === index + 1
      && UUID_PATTERN.test(step.operationId ?? '')
      && Number.isFinite(Date.parse(step.completedAt))));
  pass('ARL07', safeProgress(run));

  currentCheck = 'ARL08';
  pass('ARL08', isDeepStrictEqual(await post(messages, request), completed));
  currentCheck = 'ARL09';
  pass('ARL09', isDeepStrictEqual(await post(`${runPath()}/resume`), completed));
  currentCheck = 'ARL10';
  const afterReplay = await json(await call('GET', runPath()));
  pass('ARL10', isDeepStrictEqual(afterReplay, run));
  currentCheck = 'ARL11';
  const history = await json(await call('GET', messages));
  pass('ARL11', history.conversation?.id === conversationId && history.messages?.length === 2
    && isDeepStrictEqual(history.messages, completed.messages) && history.nextBeforeId === null);

  currentCheck = 'ARL12';
  await json(await call('GET', runPath(101), { expected: 404 }));
  pass('ARL12', true);
  currentCheck = 'ARL13';
  await post(`${runPath(101)}/resume`, undefined, 404);
  pass('ARL13', true);
  currentCheck = 'ARL14';
  await post(messages, { ...request, message: CONFLICTING_MESSAGE }, 409);
  pass('ARL14', true);
  currentCheck = 'ARL15';
  const finalRun = await json(await call('GET', runPath()));
  const finalHistory = await json(await call('GET', messages));
  pass('ARL15', isDeepStrictEqual(finalRun, run) && finalHistory.messages?.length === 2
    && isDeepStrictEqual(finalHistory.messages, completed.messages));
  process.stdout.write(`AGENT_RUNTIME_LAB=PASS checks=${passed} conversationTurnsCreated=1 businessWrites=0 modelCalls=0\n`);
}

main().catch(() => {
  process.stderr.write(`AGENT_RUNTIME_LAB=FAIL check=${currentCheck} passed=${passed}\n`);
  process.exitCode = 1;
});
