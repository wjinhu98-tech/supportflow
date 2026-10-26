import { readFile, readdir, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = fileURLToPath(new URL('../', import.meta.url))
const directory = path.join(root, '.local/capacity-results')
const queries = {
  processCpu: 'process_cpu_usage{job="supportflow-backend"}',
  jvmUsedBytes: 'sum(jvm_memory_used_bytes{job="supportflow-backend"})',
  poolActive: 'hikaricp_connections_active{job="supportflow-backend"}',
  poolPending: 'hikaricp_connections_pending{job="supportflow-backend"}',
  poolTimeouts: 'hikaricp_connections_timeout_total{job="supportflow-backend"}',
}

async function run() {
  const candidates = (await readdir(directory)).filter(name => /^\d{4}-.*Z-[0-9a-f-]{36}\.json$/.test(name)).sort()
  const latest = candidates.at(-1)
  if (!latest) throw new Error('NO_CAPACITY_REPORT')
  const source = JSON.parse(await readFile(path.join(directory, latest), 'utf8'))
  const start = Date.parse(source.startedAt) / 1000
  const end = Date.parse(source.finishedAt) / 1000
  if (source.target !== 'http://127.0.0.1:18090' || source.status !== 'COMPLETED'
    || !Number.isFinite(start) || !Number.isFinite(end) || end < start || end - start > 600) {
    throw new Error('INVALID_LAB_REPORT')
  }
  const metrics = {}
  for (const [name, query] of Object.entries(queries)) {
    const url = new URL('http://127.0.0.1:19090/api/v1/query_range')
    url.searchParams.set('query', query)
    url.searchParams.set('start', String(start))
    url.searchParams.set('end', String(end))
    url.searchParams.set('step', '15')
    const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(8000) })
    const body = await response.json()
    if (response.status !== 200 || body.status !== 'success' || !Array.isArray(body.data?.result)) {
      throw new Error('PROMETHEUS_QUERY_FAILED')
    }
    const series = body.data.result.map(item => ({ values: item.values
      .map(([time, value]) => [time, Number(value)]).filter(([, value]) => Number.isFinite(value)) }))
    const values = series.flatMap(item => item.values.map(([, value]) => value))
    metrics[name] = { query, sampleCount: values.length, status: values.length ? 'SAMPLED' : 'NO_SERIES',
      min: values.length ? Math.min(...values) : null, max: values.length ? Math.max(...values) : null, series }
  }
  if (!metrics.processCpu.sampleCount || !metrics.jvmUsedBytes.sampleCount || !metrics.poolActive.sampleCount) {
    throw new Error('REQUIRED_MONITORING_DATA_MISSING')
  }
  const report = { sourceReport: latest, startedAt: source.startedAt, finishedAt: source.finishedAt,
    samplingStepSeconds: 15, note: 'Scrape samples during this benchmark; not continuous peaks or production limits.', metrics }
  const filename = latest.replace(/\.json$/, '.monitoring.json')
  await writeFile(path.join(directory, filename), JSON.stringify(report, null, 2), { flag: 'wx' })
  console.log(`CAPACITY_MONITORING=PASS report=.local/capacity-results/${filename}`)
  for (const [name, metric] of Object.entries(metrics)) console.log(`${name}: samples=${metric.sampleCount} max=${metric.max}`)
}

run().catch(() => {
  console.error('CAPACITY_MONITORING=FAILED; keep the original report and inspect the isolated Prometheus data.')
  process.exitCode = 1
})
