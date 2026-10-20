import { test, expect } from '@playwright/test'

test('browser saves a record through the gateway and reads it after reload', async ({ page }) => {
  const token = process.env.PROBE_TOKEN
  if (!token) throw new Error('Load deploy/.env.local before running this test.')
  const label = `browser-${Date.now()}`
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '开发环境验证' })).toBeVisible()
  await page.locator('input[type=password]').fill(token)
  await page.locator('input[maxlength="80"]').fill(label)
  await page.getByRole('button', { name: '保存并验证' }).click()
  await expect(page.getByText('记录已保存，并从数据库重新读取。')).toBeVisible()
  await expect(page.getByRole('cell', { name: label, exact: true })).toBeVisible()
  await page.reload()
  await page.locator('input[type=password]').fill(token)
  await page.getByRole('button', { name: '读取记录' }).click()
  await expect(page.getByRole('cell', { name: label, exact: true })).toBeVisible()
  // Clear the development credential before capturing a shareable screenshot.
  await page.locator('input[type=password]').fill('')
  await page.screenshot({ path: '../.runtime/browser-probe.png', fullPage: true })
})
