import {test,expect} from '@playwright/test'
import {createServer} from 'node:https'
import {readFile,writeFile} from 'node:fs/promises'
import {once} from 'node:events'

test('third compatible supplier through the gateway page',async({page,request})=>{
 test.skip(!process.env.THIRD_PROVIDER_PFX,'Use Test-AiThirdProvider.ps1')
 test.setTimeout(120000)
 let compatible=false,hold=false,explanationCount=0
 let release!:()=>void,entered!:()=>void
 const released=new Promise<void>(resolve=>{release=resolve})
 const started=new Promise<void>(resolve=>{entered=resolve})
 const outputs:number[]=[]
 const server=createServer({pfx:await readFile(process.env.THIRD_PROVIDER_PFX!),passphrase:process.env.THIRD_PROVIDER_PASS},async(req,res)=>{
  try{
   const chunks:Buffer[]=[]
   for await(const chunk of req)chunks.push(Buffer.from(chunk))
   const body=JSON.parse(Buffer.concat(chunks).toString())
   if(req.url!=='/v1/chat/completions'||body.model!=='local-third'||req.headers.authorization!=='Bearer local-third-test'){
    res.writeHead(403).end();return
   }
   outputs.push(body.max_tokens)
   const tool=body.tools?.[0]?.function.name
   const content=body.messages.at(-1).content
   const marker=content.split(' ').at(-1)
   let message:unknown={role:'assistant',content:JSON.stringify({marker})}
   if(compatible&&tool){
    const args=tool==='probe'?{marker}:{reasonCodes:JSON.parse(content).reasonCodes}
    if(tool==='explainBill'){explanationCount++;entered();if(hold)await released}
    message={role:'assistant',tool_calls:[{type:'function',id:'third-call',function:{name:tool,arguments:JSON.stringify(args)}}]}
   }
   res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify({choices:[{message}],usage:{prompt_tokens:50,completion_tokens:10}}))
  }catch{res.writeHead(500).end()}
 })
 server.listen(0,'127.0.0.1');await once(server,'listening')
 const address=server.address()
 if(!address||typeof address==='string')throw Error('Missing listener')
 const base='http://127.0.0.1:9090/api/v1'
 async function login(name:string){
  const r=await request.post(base+'/auth/login',{data:{username:name,password:process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']}})
  expect(r.ok()).toBeTruthy();return {Authorization:'Bearer '+(await r.json()).data.accessToken}
 }
 const admin=await login('admin'),driver=await login('driver')
 async function state(){return (await (await request.get(base+'/admin/ai-gateway',{headers:admin})).json()).data}
 const initial=await state(),original=String(initial.route.providerRevisionId)
 const processes=await readFile('../.runtime/business/processes.json','utf8')
 const sessions:string[]=[]
 let revision='',inFlight:Promise<any>|undefined
 async function ask(reservationId:string){
  const session=(await (await request.post(base+'/ai/sessions',{headers:driver,data:{}})).json()).data.sessionId
  sessions.push(session)
  const r=await request.post(base+'/ai/sessions/'+session+'/messages',{headers:{...driver,'Idempotency-Key':crypto.randomUUID()},data:{text:'解释本人账单',expectedSessionVersion:0,context:{reservationId}}})
  expect(r.ok()).toBeTruthy();return (await r.json()).data
 }
 try{
  const precheck=await request.post(base+'/admin/ai-gateway/providers/'+original+'/check',{headers:admin,data:{}})
  expect(precheck.ok()).toBeTruthy()
  await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('admin')
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_ADMIN_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await expect(page.getByText('登录成功',{exact:true})).toBeVisible()
  await page.goto('/#/admin/ai-gateway')
  const url='https://127.0.0.1:'+address.port+'/v1'
  await page.getByLabel('名称',{exact:true}).fill('本地兼容供应商验收')
  await page.getByLabel('接口地址').fill(url)
  await page.getByLabel('模型名称').fill('local-third')
  await page.getByLabel('本地凭据文件名').fill(process.env.THIRD_PROVIDER_REF!)
  await page.getByLabel('价格版本').fill('local-v1')
  await page.getByLabel('单次最大输出 Token').fill('512')
  if(!initial.paidBudgets.some((b:any)=>b.supplierHost==='127.0.0.1')){
   await page.getByLabel('计费方式').selectOption('PAID')
   await page.getByLabel('供应商累计额度').fill('0.5')
   await page.getByLabel('授权此供应商').check()
  }
  await page.getByRole('button',{name:'保存新版本'}).click()
  await expect(page.getByText('已保存新的供应商配置版本。')).toBeVisible()
  const provider=(await state()).providers.find((p:any)=>p.baseUrl===url && p.credentialRef===process.env.THIRD_PROVIDER_REF)
  revision=String(provider.id)
  const row=page.getByRole('row').filter({has:page.getByRole('cell',{name:revision,exact:true})})
  await row.getByRole('button',{name:'检查配置'}).click()
  await expect(page.getByRole('status')).toContainText('工具调用')
  await row.getByRole('button',{name:'设为活动配置'}).click()
  await expect(page.getByRole('status')).toContainText('先完成配置检查')
  expect(String((await state()).route.providerRevisionId)).toBe(original)
  compatible=true
  await row.getByRole('button',{name:'检查配置'}).click()
  await expect(page.getByRole('status')).toContainText('配置检查通过')
  await row.getByRole('button',{name:'设为活动配置'}).click()
  await expect.poll(async()=>String((await state()).route.providerRevisionId)).toBe(revision)
  const active=(await state()).route.version
  const orders=(await (await request.get(base+'/reservations',{headers:driver})).json()).data
  const order=orders.find((o:any)=>o.ruleSnapshot&&['CONFIRMED','COMPLETED'].includes(o.status))
  expect(order).toBeTruthy()
  hold=true;inFlight=ask(order.reservationId);inFlight.catch(()=>{})
  await Promise.race([started,inFlight.then(()=>{throw Error('No in-flight third supplier request')})])
  await page.getByRole('button',{name:'恢复上一配置'}).click()
  await expect.poll(async()=>String((await state()).route.providerRevisionId)).toBe(original)
  release()
  const old=await inFlight
  expect(old.status).toBe('ANSWERED');expect(old.routeVersion).toBe(active)
  const fresh=await ask(order.reservationId)
  expect(fresh.status).toBe('ANSWERED');expect(fresh.routeVersion).toBe((await state()).route.version)
  expect(explanationCount).toBe(1);expect(outputs.every(v=>v===512)).toBe(true)
  expect(await readFile('../.runtime/business/processes.json','utf8')).toBe(processes)
  await writeFile(process.env.BUSINESS_REPORT_DIR+'/third-provider.json',JSON.stringify({revision,original,active,restored:fresh.routeVersion,oldRequest:old.requestId,newRequest:fresh.requestId,outputLimits:outputs,processesUnchanged:true},null,2))
 }finally{
  release();if(inFlight)await inFlight.catch(()=>{})
  try{
   if(String((await state()).route.providerRevisionId)!==original){
    await request.post(base+'/admin/ai-gateway/providers/'+original+'/check',{headers:admin,data:{}})
    const restored=await request.post(base+'/admin/ai-gateway/publish',{headers:admin,data:{version:(await state()).route.version,providerRevisionId:original}})
    expect(restored.ok()).toBeTruthy()
   }
   if(revision)expect((await request.patch(base+'/admin/ai-gateway/providers/'+revision+'/status',{headers:admin,data:{enabled:false}})).ok()).toBeTruthy()
   for(const id of sessions)await request.delete(base+'/ai/sessions/'+id,{headers:driver})
  }finally{server.closeAllConnections();server.close()}
 }
})
