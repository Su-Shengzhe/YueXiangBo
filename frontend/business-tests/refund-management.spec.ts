import { test, expect } from '@playwright/test'

test('refund retry preserves its original key after reload and unknown response', async ({page})=>{
  const keys:string[]=[]
  await page.route('**/api/v1/admin/refund-attempts?*',route=>route.fulfill({json:{data:{items:[{id:'900001',reservationId:'900002',amountFen:500,status:'FAILED',retryOfId:null}],total:1}}}))
  await page.route('**/api/v1/admin/refund-attempts/900001/retry',async route=>{
    keys.push(route.request().headers()['idempotency-key'])
    if(keys.length===1)await route.abort('failed')
    else await route.fulfill({status:202,json:{data:{id:'900003'}}})
  })
  async function login(){
    await page.getByLabel('账号',{exact:true}).fill('admin')
    await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_ADMIN_PASSWORD!)
    await page.getByRole('button',{name:'登录',exact:true}).click()
    await page.getByRole('button',{name:'退款执行',exact:true}).click()
    await expect(page.getByRole('heading',{name:'退款执行记录'})).toBeVisible()
  }
  await page.goto('/');await login()
  await page.getByRole('button',{name:'重试',exact:true}).click()
  await page.getByLabel('处理原因').fill('核查后恢复')
  await page.getByRole('button',{name:'确认重试',exact:true}).click()
  await expect(page.getByRole('button',{name:'按原请求核对重试'})).toBeEnabled()
  await page.reload();await login()
  await expect(page.getByLabel('处理原因')).toHaveValue('核查后恢复')
  await page.getByRole('button',{name:'按原请求核对重试'}).click()
  await expect(page.getByRole('status').filter({hasText:'已受理，后继记录'})).toContainText('900003')
  expect(keys).toHaveLength(2)
  expect(keys[0]).toBeTruthy()
  expect(keys[1]).toBe(keys[0])
})

test('refund management is reachable by platform and denied to driver', async ({page,request})=>{
  const base='http://127.0.0.1:9090/api/v1'
  const driver=await request.post(base+'/auth/login',{data:{username:'driver',password:process.env.DEMO_DRIVER_PASSWORD}})
  expect(driver.ok()).toBeTruthy()
  const token=(await driver.json()).data.accessToken
  const denied=await request.get(base+'/admin/refund-attempts',{headers:{Authorization:'Bearer '+token}})
  expect(denied.status()).toBe(403)
  await page.goto('/')
  await page.getByLabel('账号',{exact:true}).fill('admin')
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_ADMIN_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:'退款执行',exact:true}).click()
  await expect(page.getByRole('heading',{name:'退款执行记录'})).toBeVisible()
  const response=page.waitForResponse(r=>r.url().includes('/api/v1/admin/refund-attempts?'))
  await page.getByRole('button',{name:'刷新状态',exact:true}).click()
  expect((await response).status()).toBe(200)
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await page.getByLabel('账号',{exact:true}).fill('driver')
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_DRIVER_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await expect(page.getByRole('button',{name:'退出登录',exact:true})).toBeVisible()
  await expect(page.getByRole('button',{name:'退款执行',exact:true})).toHaveCount(0)
  await expect(page.getByRole('heading',{name:'退款执行记录'})).toHaveCount(0)
})
