import {test,expect} from '@playwright/test'
import {readFile} from 'node:fs/promises'

test('AI live browser switch preserves in-flight route',async({page,request},info)=>{
 test.skip(process.env.AI_LIVE_SWITCH!=='true','Explicit live paid acceptance opt-in required')
 test.setTimeout(120000)
 const base='http://127.0.0.1:9090/api/v1'
 async function login(name:string){const r=await request.post(base+'/auth/login',{data:{username:name,password:process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']}});expect(r.ok()).toBeTruthy();return (await r.json()).data.accessToken}
 const admin=await login('admin'),driver=await login('driver'),ah={Authorization:'Bearer '+admin},dh={Authorization:'Bearer '+driver}
 async function state(){const r=await request.get(base+'/admin/ai-gateway',{headers:ah});expect(r.ok()).toBeTruthy();return (await r.json()).data}
 const initial=await state(),original=String(initial.route.providerRevisionId),target=initial.providers.find((p:any)=>p.baseUrl==='https://api.deepseek.com'&&p.paidScope==='DEEPSEEK_ACCEPTANCE')
 expect(target).toBeTruthy();expect(String(target.id)).not.toBe(original)
 const processes=await readFile('../.runtime/business/processes.json','utf8'),sessions:string[]=[],evidence:any[]=[]
 let inFlight:Promise<any>|undefined
 try{
  for(const id of [original,target.id]){const provider=initial.providers.find((p:any)=>String(p.id)===String(id));if(!provider.checkedAt||Date.now()-Date.parse(provider.checkedAt)>480000){const check=await request.post(base+`/admin/ai-gateway/providers/${id}/check`,{headers:ah,data:{},timeout:25000});expect(check.ok(),JSON.stringify(await check.json())).toBeTruthy()}}
  await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('admin');await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_ADMIN_PASSWORD!);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByText('登录成功',{exact:true})).toBeVisible();await page.goto('/#/admin/ai-gateway')
  // Exercise a new paid-host form without authorizing any real third-party spend.
  let submitted:Record<string,unknown>|undefined
  const createRoute='**/api/v1/admin/ai-gateway/providers'
  await page.route(createRoute,async route=>{
   submitted=route.request().postDataJSON()
   await route.fulfill({json:{code:'OK',data:{id:'test-only'}}})
  })
  await page.getByLabel('接口地址').fill('https://third.example/v1')
  await page.getByLabel('计费方式').selectOption('PAID')
  await page.getByLabel('供应商累计额度').fill('0.5')
  await page.getByLabel('授权此供应商').check()
  await page.getByRole('button',{name:'保存新版本'}).click()
  await expect.poll(()=>submitted?.paidLimitMicroyuan).toBe(500000)
  expect(submitted?.paidApproved).toBe(true)
  await expect(page.getByText('已保存新的供应商配置版本。')).toBeVisible()
  await page.unroute(createRoute)
  async function create(){const r=await request.post(base+'/ai/sessions',{headers:dh,data:{}});expect(r.ok()).toBeTruthy();const id=(await r.json()).data.sessionId;sessions.push(id);return id}
  const orders=(await (await request.get(base+'/reservations',{headers:dh})).json()).data
  const bill=orders.find((o:any)=>o.ruleSnapshot&&['CONFIRMED','COMPLETED'].includes(o.status))
  expect(bill,'A stable driver order with a rule snapshot is required').toBeTruthy()
  async function send(id:string){const r=await request.post(base+`/ai/sessions/${id}/messages`,{headers:{...dh,'Idempotency-Key':crypto.randomUUID()},data:{text:'解释本人账单',context:{reservationId:bill.reservationId},expectedSessionVersion:0},timeout:25000});expect(r.ok()).toBeTruthy();return (await r.json()).data}
  const sid=await create();inFlight=send(sid)
  await expect.poll(async()=>{const r=await request.get(base+`/ai/sessions/${sid}/messages`,{headers:dh});const h=(await r.json()).data;return h.sessionStatus==='PROCESSING'&&h.items[0]?.routeVersion===initial.route.version},{timeout:5000,intervals:[50,100]}).toBeTruthy()
  const row=page.getByRole('row').filter({has:page.getByRole('cell',{name:String(target.id),exact:true})})
  await row.getByRole('button',{name:'设为活动配置'}).click()
  await expect.poll(async()=>String((await state()).route.providerRevisionId)).toBe(String(target.id))
  const switched=await state();const old=await inFlight;expect(old.status).toBe('ANSWERED');expect(old.routeVersion).toBe(initial.route.version)
  const history=(await (await request.get(base+`/ai/sessions/${sid}/messages`,{headers:dh})).json()).data
  const message=history.items.find((x:any)=>x.requestId===old.requestId),publication=switched.history.find((x:any)=>x.version===switched.route.version)
  expect(Date.parse(message.createdAt)).toBeLessThan(Date.parse(publication.publishedAt))
  expect(Date.parse(message.finishedAt),'The original request must remain in flight at publication').toBeGreaterThan(Date.parse(publication.publishedAt))
  evidence.push({phase:'in-flight',requestId:old.requestId,routeVersion:old.routeVersion,startedAt:message.createdAt,publishedAt:publication.publishedAt,finishedAt:message.finishedAt})
  const fresh=await send(await create());expect(fresh.status).toBe('ANSWERED');expect(fresh.routeVersion).toBe(switched.route.version);evidence.push({phase:'new-route',requestId:fresh.requestId,routeVersion:fresh.routeVersion})
  await page.getByRole('button',{name:'恢复上一配置'}).click();await expect.poll(async()=>String((await state()).route.providerRevisionId)).toBe(original)
  expect(await readFile('../.runtime/business/processes.json','utf8')).toBe(processes)
 }finally{
  if(inFlight)await inFlight.catch(()=>{})
  const current=await state();if(String(current.route.providerRevisionId)!==original){const restored=await request.post(base+'/admin/ai-gateway/publish',{headers:ah,data:{version:current.route.version,providerRevisionId:original}});expect(restored.ok(),'Restore original supplier').toBeTruthy()}
  for(const id of sessions)await request.delete(base+`/ai/sessions/${id}`,{headers:dh})
  await info.attach('route-evidence',{body:JSON.stringify(evidence),contentType:'application/json'})
 }
})
