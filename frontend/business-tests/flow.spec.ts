import { test, expect } from '@playwright/test'
test('login, query, reserve, pay, property entry/exit, overtime bill through UI', async ({ page, request, browser }) => {
  const api = 'http://127.0.0.1:9090/api/v1'
  async function loginApi(name: string) {
    const response = await request.post(api + '/auth/login', { data: { username: name, password: process.env['DEMO_' + name.toUpperCase() + '_PASSWORD'] } })
    expect(response.ok()).toBeTruthy()
    return (await response.json()).data.accessToken
  }
  const admin = await loginApi('admin'), driver = await loginApi('driver')
  for(const input of [{reservationId:-1,amountFen:1.5},{reservationId:-1.5,amountFen:1}]){
    const rejected=await request.post(api+'/refund-applications',{headers:{Authorization:'Bearer '+driver,'Idempotency-Key':crypto.randomUUID()},data:{...input,reason:'验证金额和编号不能截断小数'}})
    expect(rejected.status()).toBe(422)
  }
  async function advance(now: string) {
    const response = await request.post(api + '/demo/clock', { headers: { Authorization: 'Bearer ' + admin }, data: { now } })
    expect(response.ok()).toBeTruthy()
  }
  async function loginUi(name: string) {
    await page.getByLabel('账号', { exact: true }).fill(name)
    await page.getByLabel('密码', { exact: true }).fill(process.env['DEMO_' + name.toUpperCase() + '_PASSWORD']!)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible()
  }
  await page.goto('/')
  await loginUi('driver')
  const clockResponse = await request.get(api + '/demo/clock', { headers: { Authorization: 'Bearer ' + driver } })
  let candidate = Math.ceil((new Date((await clockResponse.json()).data.now).getTime() + 1800000) / 900000) * 900000
  for(let i=0;i<10;i++){
    const available = await request.get(api + '/spaces/available', { params: { communityId: '10', startAt: new Date(candidate).toISOString(), endAt: new Date(candidate+3600000).toISOString() } })
    if((await available.json()).data?.length) break
    candidate += 3600000
  }
  const times = await page.evaluate(value => [value,value+3600000].map(t => new Date(t+8*3600000).toISOString().slice(0,16)), candidate)
  await page.getByLabel('开始时间', { exact:true }).fill(times[0])
  await page.getByLabel('结束时间', { exact:true }).fill(times[1])
  await page.getByRole('button', { name: '查询车位', exact: true }).click()
  await expect(page.getByRole('button', { name: '预约此车位' })).toBeVisible()
  await page.getByRole('button', { name: '预约此车位' }).click()
  await expect(page.getByRole('heading', { name: '我的预约', exact: true })).toBeVisible()
  const ordersResponse = await request.get(api + '/reservations', { headers: { Authorization: 'Bearer ' + driver } })
  const order = (await ordersResponse.json()).data[0]
  const row = page.getByRole('row').filter({ hasText: order.reservationId })
  await row.getByRole('button', { name: '查看账单' }).click()
  await expect.poll(async () => {
    await page.getByRole('button', { name: '刷新账单' }).click()
    await expect(page.getByRole('button', { name: '刷新账单' })).toBeEnabled()
    return page.getByRole('button', { name: '模拟支付预约费' }).count()
  }, { timeout: 15000 }).toBe(1)
  await page.getByRole('button', { name: '模拟支付预约费' }).click()
  await expect(page.locator('.bill .status')).toHaveText('预约已确认', { timeout: 20000 })
  await advance(order.startAt)
  await page.getByRole('button', { name: '退出登录' }).click()
  await loginUi('property')
  await page.getByRole('button', { name: '刷新列表' }).click()
  const propertyRow = page.getByRole('row').filter({ hasText: order.reservationId })
  await propertyRow.getByRole('button', { name: '办理入场' }).click()
  await expect(propertyRow).toContainText('停车中')
  await advance(new Date(new Date(order.endAt).getTime() + 1000).toISOString())
  await propertyRow.getByRole('button', { name: '办理离场' }).click()
  await expect(propertyRow).toContainText('已离场')
  await page.getByRole('button', { name: '退出登录' }).click()
  await loginUi('driver')
  await page.getByRole('button', { name: '我的预约与账单' }).click()
  await page.getByRole('row').filter({ hasText: order.reservationId }).getByRole('button', { name: '查看账单' }).click()
  await expect(page.getByRole('button', { name: '模拟支付超时费' })).toBeVisible({ timeout: 15000 })
  await page.getByRole('button', { name: '模拟支付超时费' }).click()
  await expect(page.locator('.amounts > div').filter({ hasText: '已付合计' }).locator('b')).toHaveText('¥7.50', { timeout: 20000 })
  await page.screenshot({ path: process.env.BUSINESS_REPORT_DIR + '/bill-page.png', fullPage: true })
  await test.info().attach('browser-version', { body: browser.version(), contentType: 'text/plain' })
  await page.getByRole('button',{name:'我的预约与账单',exact:true}).click()
  await page.getByLabel('预约编号',{exact:true}).fill(String(order.reservationId))
  await page.getByLabel('申请金额（元）',{exact:true}).fill('1.00')
  await page.getByLabel('退款原因',{exact:true}).fill('核实服务差异，申请部分退款')
  await page.getByRole('button',{name:'提交退款申请',exact:true}).click()
  await expect(page.getByText('申请已受理，等待审批。',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await loginUi('admin')
  await page.getByRole('button',{name:'退款执行',exact:true}).click()
  await page.getByLabel('审批说明',{exact:true}).fill('核查后批准部分退款')
  const applicationRow=page.getByRole('row').filter({hasText:String(order.reservationId)}).filter({hasText:'待审批'})
  await applicationRow.getByRole('button',{name:'驳回退款',exact:true}).click()
  await expect(page.getByText('审批结果已确认，退款结果以执行状态为准。',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await loginUi('driver')
  await page.getByRole('button',{name:'我的预约与账单',exact:true}).click()
  await page.getByRole('row').filter({hasText:String(order.reservationId)}).filter({hasText:'已驳回'}).getByRole('button',{name:'补充证据重提'}).click()
  await page.getByLabel('证据说明',{exact:true}).fill('补充现场记录，证明服务存在差异')
  await page.getByRole('button',{name:'提交退款申请',exact:true}).click()
  await expect(page.getByText('申请已受理，等待审批。',{exact:true})).toBeVisible()
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await loginUi('admin')
  await page.getByRole('button',{name:'退款执行',exact:true}).click()
  await page.getByLabel('审批说明',{exact:true}).fill('新增证据核验通过')
  const decisionKeys:string[]=[]
  let loseDecisionResponse=true
  await page.route('**/refund-applications/*/decision',async route=>{
    decisionKeys.push(route.request().headers()['idempotency-key']||'')
    if(loseDecisionResponse){loseDecisionResponse=false;await route.fetch();await route.abort('failed')}
    else await route.continue()
  })
  await applicationRow.getByRole('button',{name:'批准退款',exact:true}).click()
  await expect(page.getByRole('button',{name:'核对未确认审批'})).toBeVisible()
  await expect(page.getByRole('button',{name:'核对未确认审批'})).toBeEnabled()
  await page.reload()
  await loginUi('admin')
  await page.getByRole('button',{name:'退款执行',exact:true}).click()
  await expect(page.getByRole('button',{name:'核对未确认审批'})).toBeVisible()
  await page.getByRole('button',{name:'核对未确认审批'}).click()
  await expect(page.getByText('审批结果已确认，退款结果以执行状态为准。',{exact:true})).toBeVisible()
  expect(decisionKeys).toHaveLength(2);expect(decisionKeys[0]).toBeTruthy();expect(decisionKeys[1]).toBe(decisionKeys[0])
  await expect.poll(async()=>{
    const response=await request.get(api+'/bills/'+order.reservationId,{headers:{Authorization:'Bearer '+driver}})
    return (await response.json()).data.refundFen
  },{timeout:20000}).toBe(100)
  await page.getByRole('button',{name:'刷新退款申请',exact:true}).click()
  await expect(page.getByRole('row').filter({hasText:String(order.reservationId)}).filter({hasText:'退款完成'})).toHaveCount(1)
  await page.getByRole('button',{name:'退出登录',exact:true}).click()
  await loginUi('driver')
  await page.getByRole('button', { name: '停车助手', exact:true }).click()
  await expect(page.getByRole('heading',{name:'AI停车助手'})).toBeVisible()
  await expect(page.getByRole('button',{name:'发送',exact:true})).toBeEnabled()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '欢迎登录' })).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.length + localStorage.length)).toBe(0)
})
