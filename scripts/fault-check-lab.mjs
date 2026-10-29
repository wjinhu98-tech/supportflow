import { execFile } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { performance } from 'node:perf_hooks'
import { createSessionClient } from './session-client.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const origin = 'http://127.0.0.1:18090'
const management = 'http://127.0.0.1:18091'
const monitoring = 'http://127.0.0.1:19090'
const compose = ['compose', '--project-name', 'supportflow-lab', '-f', path.join(root, 'deploy/compose.yml')]
const touched = new Set()
const observations = []
const session = createSessionClient(origin, { cookieName: 'SF_LAB_SESSION', marker: 'isolated-lab' })

function ensure(value, code) { if (!value) throw new Error(code) }
function docker(args) {
  return new Promise((resolve, reject) => execFile('docker', args,
    { cwd: root, shell: false, windowsHide: true, timeout: 60000, maxBuffer: 65536 },
    (error, stdout) => error ? reject(new Error('LAB_DOCKER_COMMAND_FAILED')) : resolve(stdout)))
}

async function waitFor(name, probe, seconds = 90) {
  const started = performance.now()
  while (performance.now() - started < seconds * 1000) {
    try {
      if (await probe()) {
        observations.push({ name, elapsedMs: Math.round(performance.now() - started), passed: true })
        console.log(`FAULT_CHECK=${name}:PASS`)
        return
      }
    } catch { /* A temporary transport failure is expected while the selected service is stopped. */ }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('LAB_PROBE_DEADLINE')
}

async function getHealth(group, code, state) {
  const response = await fetch(`${management}/actuator/health/${group}`, { redirect: 'error', signal: AbortSignal.timeout(5000) })
  const body = await response.json()
  return response.status === code && body.status === state && !body.details && !body.components
}

async function api(endpoint, body, expected = 200) {
  let headers = {}
  if (body !== undefined) {
    const csrf = await session.csrf()
    headers = { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json' }
  }
  const response = await session.fetch(endpoint, { method: body === undefined ? 'GET' : 'POST', headers,
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), redirect: 'error', signal: AbortSignal.timeout(8000) })
  ensure(response.status === expected, 'LAB_API_UNEXPECTED_STATUS')
  ensure(response.headers.get('x-supportflow-environment') === 'isolated-lab', 'LAB_ENVIRONMENT_MISMATCH')
  return response.json()
}

async function queryUp(expected) {
  const response = await fetch(`${monitoring}/api/v1/query?query=up%7Bjob%3D%22supportflow-backend%22%7D`,
    { redirect: 'error', signal: AbortSignal.timeout(5000) })
  const json = await response.json()
  return json.status === 'success' && json.data?.result?.some(item => item.value?.[1] === String(expected))
}

async function alertFiring(expected) {
  const response = await fetch(`${monitoring}/api/v1/alerts`, { redirect: 'error', signal: AbortSignal.timeout(5000) })
  const json = await response.json()
  const firing = json.data?.alerts?.some(alert => alert.labels?.alertname === 'SupportFlowLabScrapeUnavailable' && alert.state === 'firing')
  return json.status === 'success' && Boolean(firing) === expected
}

async function verifyHistory(id, expectedIds) {
  const history = await api(`/api/v1/shops/100/chat/conversations/${id}/messages`)
  ensure(history.messages?.length === 2, 'LAB_HISTORY_LENGTH')
  ensure(history.messages.every((message, index) => message.id === expectedIds[index]), 'LAB_HISTORY_IDENTITY')
  ensure(history.messages[1].response?.orders?.[0]?.orderNo === 'LAB-000001', 'LAB_HISTORY_ORDER')
}

async function run() {
  const password = (await readFile(path.join(root, '.local/deployment-lab/lab_buyer_password'), 'utf8')).trim()
  ensure(/^[A-Za-z0-9_-]{24,128}$/.test(password), 'INVALID_LAB_CREDENTIAL')
  await session.login('lab-buyer', password)
  const me = await api('/api/v1/security/me')
  ensure(me.username === 'lab-buyer' && me.accountType === 'BUYER', 'LAB_IDENTITY_MISMATCH')
  for (const service of ['mysql', 'backend']) {
    const labels = JSON.parse(await docker(['inspect', '--format', '{{json .Config.Labels}}', `supportflow-lab-${service}-1`]))
    ensure(labels['com.docker.compose.project'] === 'supportflow-lab'
      && labels['com.docker.compose.service'] === service, 'LAB_CONTAINER_IDENTITY')
  }
  await waitFor('initial-readiness', () => getHealth('readiness', 200, 'UP'))
  await waitFor('initial-prometheus-up', () => queryUp(1))
  const created = await api('/api/v1/shops/100/chat/conversations', {}, 201)
  ensure(/^[0-9a-f-]{36}$/.test(created.id), 'LAB_CONVERSATION_ID')
  const turn = await api(`/api/v1/shops/100/chat/conversations/${created.id}/messages`,
    { clientMessageId: randomUUID(), message: '查询此订单', orderNo: 'LAB-000001' })
  const ids = turn.messages.map(message => message.id)
  await verifyHistory(created.id, ids)

  touched.add('mysql')
  await docker([...compose, 'stop', 'mysql'])
  await waitFor('database-down-readiness-503', () => getHealth('readiness', 503, 'DOWN'), 40)
  await waitFor('database-down-liveness-stays-up', () => getHealth('liveness', 200, 'UP'), 20)
  await docker([...compose, 'start', 'mysql'])
  await waitFor('database-recovered-readiness', () => getHealth('readiness', 200, 'UP'), 90)
  await verifyHistory(created.id, ids)
  observations.push({ name: 'history-after-database-restart', passed: true })

  touched.add('backend')
  await docker([...compose, 'stop', 'backend'])
  await waitFor('backend-down-prometheus-zero', () => queryUp(0), 45)
  await waitFor('scrape-alert-firing', () => alertFiring(true), 120)
  await docker([...compose, 'start', 'backend'])
  await waitFor('backend-recovered-readiness', () => getHealth('readiness', 200, 'UP'), 90)
  await waitFor('prometheus-target-recovered', () => queryUp(1), 45)
  await waitFor('scrape-alert-resolved', () => alertFiring(false), 45)
  // HttpSession is intentionally in memory; reauthenticate after a backend restart before reading persisted business history.
  await session.login('lab-buyer', password)
  await verifyHistory(created.id, ids)
  const order = await api('/api/v1/shops/100/orders/LAB-000001')
  ensure(order.status === 'SHIPPED' && order.totalAmount === 199.9, 'LAB_BUSINESS_DATA_CHANGED')
  observations.push({ name: 'history-and-order-after-backend-restart', passed: true })
  const directory = path.join(root, '.local/deployment-checks')
  await mkdir(directory, { recursive: true })
  const report = path.join(directory, `fault-${new Date().toISOString().replaceAll(':', '-')}.json`)
  await writeFile(report, JSON.stringify({ timestamp: new Date().toISOString(), observations,
    result: 'PASS', businessWrites: 0, conversationTurnsCreated: 1, modelCalls: 0 }, null, 2), { flag: 'wx' })
  console.log(`FAULT_CHECKS=PASS report=${path.relative(root, report)}`)
}

run().catch(() => {
  console.error('FAULT_CHECKS=FAILED; inspect the isolated stack. No credentials or response bodies are printed.')
  process.exitCode = 1
}).finally(async () => {
  for (const service of touched) {
    try { await docker([...compose, 'start', service]) }
    catch { console.error(`LAB_RESTORE_REQUIRED service=${service}`); process.exitCode = 1 }
  }
})
