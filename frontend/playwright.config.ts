import { defineConfig, devices } from '@playwright/test'

const liveBackend = process.env.E2E_BACKEND_URL
const backend = liveBackend ?? 'http://127.0.0.1:18080'
const backendUrl = new URL(backend)
if (
  !['127.0.0.1', 'localhost', '[::1]'].includes(backendUrl.hostname) ||
  backendUrl.protocol !== 'http:'
) {
  throw new Error('Browser integration tests may only target an isolated loopback HTTP backend.')
}
const channel = process.env.PLAYWRIGHT_CHANNEL ?? 'msedge'
const frontendPort = Number(process.env.E2E_FRONTEND_PORT ?? '5173')
if (!Number.isInteger(frontendPort) || frontendPort < 1 || frontendPort > 65535) {
  throw new Error('E2E_FRONTEND_PORT must be a valid local TCP port.')
}
const frontend = `http://127.0.0.1:${frontendPort}`

export default defineConfig({
  testDir: './e2e',
  testMatch: liveBackend
    ? [
        '**/live.spec.ts',
        '**/knowledge-live.spec.ts',
        '**/context-live.spec.ts',
        '**/support-live.spec.ts',
      ]
    : ['**/app.spec.ts', '**/support.spec.ts'],
  outputDir: liveBackend ? 'test-results/live' : 'test-results/mock',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 7_000 },
  reporter: 'list',
  use: {
    baseURL: frontend,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    headless: true,
    ...(channel === 'chromium' ? {} : { channel }),
  },
  projects: [
    {
      name: 'desktop-edge',
      use: { ...devices['Desktop Edge'], viewport: { width: 1440, height: 1000 } },
    },
  ],
  webServer: [
    ...(!liveBackend
      ? [
          {
            command: 'node e2e/mock-server.mjs',
            url: 'http://127.0.0.1:18080/__e2e/health',
            reuseExistingServer: false,
            timeout: 20_000,
          },
        ]
      : []),
    {
      command: `npm run dev -- --host 127.0.0.1 --port ${frontendPort} --strictPort`,
      url: frontend,
      env: { API_PROXY_TARGET: backend },
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
})
