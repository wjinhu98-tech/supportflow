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

export default defineConfig({
  testDir: './e2e',
  testMatch: liveBackend ? ['**/live.spec.ts', '**/knowledge-live.spec.ts'] : '**/app.spec.ts',
  outputDir: liveBackend ? 'test-results/live' : 'test-results/mock',
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 30_000,
  expect: { timeout: 7_000 },
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
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
      command: 'npm run dev -- --host 127.0.0.1 --port 5173 --strictPort',
      url: 'http://127.0.0.1:5173',
      env: { API_PROXY_TARGET: backend },
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
})
