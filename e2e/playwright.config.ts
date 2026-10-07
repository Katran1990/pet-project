import { randomBytes } from 'node:crypto'
import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.E2E_BASE_URL
if (!baseURL) {
  throw new Error(
    'Set E2E_BASE_URL to the deployed frontend, e.g. E2E_BASE_URL=https://<host> npm run e2e',
  )
}

// Set in the main process, so every worker inherits the same id. CI sets it to gh-<run>-<attempt>.
process.env.E2E_RUN_ID ??= `local-${Date.now().toString(36)}-${randomBytes(2).toString('hex')}`
if (!/^[a-z0-9-]{1,32}$/.test(process.env.E2E_RUN_ID)) {
  throw new Error(`E2E_RUN_ID must match ^[a-z0-9-]{1,32}$, got "${process.env.E2E_RUN_ID}"`)
}

export default defineConfig({
  testDir: './tests',
  outputDir: 'test-results',
  forbidOnly: !!process.env.CI,
  // No retries: flakiness on a shared environment should be visible, not retried away.
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Matches the default APP_TIME_ZONE: the expense form pre-fills "today" in the browser's
    // zone and the backend rejects a date after today (ADR 0015).
    timezoneId: 'Europe/Warsaw',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
