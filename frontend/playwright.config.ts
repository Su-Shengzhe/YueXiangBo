import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  reporter: [['list'], ['json', { outputFile: process.env.PROBE_BROWSER_REPORT || '../.runtime/browser-report.json' }]],
  metadata: { node: process.version, channel: 'msedge' },
  workers: 1,
  retries: 0,
  use: {
    channel: 'msedge',
    headless: true,
    baseURL: 'http://127.0.0.1:5173',
    trace: 'off',
    screenshot: 'off',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://127.0.0.1:5173',
    reuseExistingServer: false,
    timeout: 60000,
  },
})
