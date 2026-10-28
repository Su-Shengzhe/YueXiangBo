import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './business-tests', workers: 1, retries: 0, timeout: 90000,
  reporter: [['list'], ['json', { outputFile: process.env.BUSINESS_REPORT_DIR + '/browser-results.json' }]],
  use: { channel: 'msedge', headless: true, timezoneId: 'America/Los_Angeles', baseURL: 'http://127.0.0.1:5174', trace: 'off', screenshot: 'off' },
  webServer: { command: 'npm run dev -- --mode business --port 5174', url: 'http://127.0.0.1:5174', reuseExistingServer: false, timeout: 60000 }
})
