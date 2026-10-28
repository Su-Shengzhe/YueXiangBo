import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './regression-tests', workers: 1, retries: 0, timeout: 30000,
  reporter: [['list'], ['json', { outputFile: '../.runtime/optimization-browser.json' }]],
  use: { channel: 'msedge', headless: true, baseURL: 'http://127.0.0.1:5176' },
  webServer: { command: 'npm run dev -- --mode business --port 5176', url: 'http://127.0.0.1:5176', reuseExistingServer: false }
})
