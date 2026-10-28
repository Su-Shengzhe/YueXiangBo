import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page) {
  let created = false
  const writes: string[] = []
  const order = (id: string) => ({ reservationId: id, plate: '测试车辆', startAt: '2030-01-01T01:00:00Z', endAt: '2030-01-01T02:00:00Z', status: 'WAIT_PAY', prepayFen: 500, payDeadline: '2030-01-01T00:10:00Z' })
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    let data: unknown
    if (path === '/auth/login') data = { accessToken: 'mock', user: { id: '1', username: 'driver', roles: ['DRIVER'] } }
    else if (path === '/communities') data = [{ id: '10', name: '测试社区' }]
    else if (path === '/vehicles') data = [{ id: '40', plate: '测试车辆' }]
    else if (path === '/demo/clock') data = { now: '2030-01-01T00:00:00Z' }
    else if (path === '/spaces/search') data = { items: [{ id: '20', name: '测试车位', hourlyFen: 500, estimatedFen: 500, windowId: '30', windowVersion: 1 }], total: 1 }
    else if (path === '/reservations') {
      if (route.request().method() === 'POST') { created = true; data = order('B') }
      else data = created ? [order('B'), order('A')] : [order('A')]
    } else if (path === '/bills/A') data = { ...order('A'), overtimeFen: 0, paidPrepayFen: 0, paidOvertimeFen: 0, refundFen: 0, attempts: [] }
    else if (path === '/bills/B') {
      await route.fulfill({ status: 404, json: { code: 'BILL_NOT_READY', message: '账单准备中' } }); return
    } else if (path === '/payments') { writes.push(route.request().postData()!); data = {} }
    else if (path === '/ai/prototype') {
      await new Promise(resolve => setTimeout(resolve, 1500)); data = { answer: '模型桩回答' }
    } else throw new Error('Unexpected request: ' + path)
    await route.fulfill({ json: { code: 'OK', data } })
  })
  await page.goto('/')
  await page.getByLabel('账号', { exact: true }).fill('driver')
  await page.getByLabel('密码', { exact: true }).fill('mock-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('button', { name: '查询车位', exact: true })).toBeEnabled()
  return writes
}

test('new reservation never renders or pays using a previous bill when loading fails', async ({ page }) => {
  const writes = await fixture(page)
  await page.getByRole('button', { name: '我的预约与账单' }).click()
  await page.getByRole('button', { name: '查看账单' }).click()
  await expect(page.locator('.bill')).toContainText('¥5.00')
  await page.getByRole('button', { name: '查询与预约' }).click()
  await page.getByRole('button', { name: '查询车位', exact: true }).click()
  await page.getByRole('button', { name: '预约此车位' }).click()
  await page.getByRole('row').filter({ hasText: 'B' }).getByRole('button', { name: '查看账单' }).click()
  await expect(page.locator('.content')).toContainText('预约号 B')
  await expect(page.locator('.bill')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '模拟支付预约费' })).toHaveCount(0)
  expect(writes).toEqual([])
})

test('changing the time invalidates the displayed quote until another query', async ({ page }) => {
  await fixture(page)
  await page.getByRole('button', { name: '查询车位', exact: true }).click()
  await expect(page.getByRole('button', { name: '预约此车位' })).toBeVisible()
  await page.getByLabel('结束时间', { exact: true }).fill('2030-01-01T12:00')
  await expect(page.getByRole('button', { name: '预约此车位' })).toHaveCount(0)
  await page.getByRole('button', { name: '查询车位', exact: true }).click()
  await expect(page.getByRole('button', { name: '预约此车位' })).toBeEnabled()
})

test('server quote change clears stale cards and asks for a fresh query', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/v1/reservations', async route => {
    if(route.request().method()==='POST')await route.fulfill({status:409,json:{code:'QUOTE_CHANGED',message:'报价或窗口已变化，请重新查询并确认'}})
    else await route.fallback()
  })
  await page.getByRole('button',{name:'查询车位',exact:true}).click()
  await page.getByRole('button',{name:'预约此车位'}).click()
  await expect(page.getByRole('status')).toContainText('重新查询')
  await expect(page.getByRole('button',{name:'预约此车位'})).toHaveCount(0)
})

test('changing price filter during a pending search discards its old results', async ({ page }) => {
  await fixture(page)
  await page.route('**/api/v1/spaces/search?**',async route=>{await new Promise(r=>setTimeout(r,500));await route.fulfill({json:{code:'OK',data:{items:[{id:'20',name:'stale',hourlyFen:500,estimatedFen:500}],total:1}}})})
  await page.getByRole('button',{name:'查询车位',exact:true}).click()
  await page.getByLabel('最高小时价格（分）',{exact:true}).fill('100')
  await expect(page.getByRole('button',{name:'查询车位',exact:true})).toBeEnabled()
  await expect(page.getByRole('button',{name:'预约此车位'})).toHaveCount(0)
})

test('slow assistant does not disable business navigation or querying', async ({ page }) => {
  await fixture(page)
  await page.getByRole('button', { name: '停车助手 · 模型桩' }).click()
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '回答中…' })).toBeDisabled()
  await page.getByRole('button', { name: '查询与预约' }).click()
  await page.getByRole('button', { name: '查询车位', exact: true }).click()
  await expect(page.getByRole('button', { name: '预约此车位' })).toBeVisible()
})

test.describe('business timezone and cancellation', () => {
  test.use({ timezoneId: 'America/Los_Angeles' })
  test('Beijing input and API instants stay consistent in another browser timezone', async ({ page }) => {
    await fixture(page)
    await expect(page.getByLabel('开始时间', { exact: true })).toHaveValue('2030-01-01T08:30')
    await expect(page.locator('.context')).toContainText('测试社区')
    const request = page.waitForRequest(r => r.url().includes('/spaces/search'))
    await page.getByRole('button', { name: '查询车位', exact: true }).click()
    expect(new URL((await request).url()).searchParams.get('startAt')).toBe('2030-01-01T00:30:00.000Z')
  })
  test('dismissed confirmation does not cancel; accepting it submits once', async ({ page }) => {
    await fixture(page)
    let calls = 0
    await page.route('**/api/v1/reservations/A/cancel', async route => { calls++; await route.fulfill({ json: { code: 'OK', data: {} } }) })
    await page.getByRole('button', { name: '我的预约与账单' }).click()
    page.once('dialog', d => d.dismiss())
    await page.getByRole('button', { name: '取消预约', exact: true }).click()
    await expect(page.getByRole('button', { name: '取消预约', exact: true })).toBeEnabled()
    expect(calls).toBe(0)
    page.once('dialog', d => d.accept())
    await page.getByRole('button', { name: '取消预约', exact: true }).click()
    await expect(page.getByRole('status')).toContainText('预约已关闭')
    expect(calls).toBe(1)
  })
  test('at the exact start boundary cancellation is disabled', async ({ page }) => {
    await fixture(page)
    await page.route('**/api/v1/demo/clock', route => route.fulfill({ json: { code: 'OK', data: { now: '2030-01-01T01:00:00Z' } } }))
    await page.getByRole('button', { name: '我的预约与账单' }).click()
    await expect(page.getByRole('button', { name: '取消预约', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: '取消预约', exact: true })).toHaveAttribute('title', '预约已开始，不能自主取消')
  })
})
