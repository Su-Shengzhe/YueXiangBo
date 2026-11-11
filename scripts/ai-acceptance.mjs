import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
const base='http://127.0.0.1:9090/api/v1',checks=[],sessions=[]
let admin,driver,other
async function request(path,token,body,method=body===undefined?'GET':'POST',key){
 const headers={};if(token)headers.Authorization='Bearer '+token;if(body!==undefined)headers['Content-Type']='application/json';if(key)headers['Idempotency-Key']=key
 const r=await fetch(base+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)})
 return {status:r.status,...await r.json()}
}
async function ok(...args){const r=await request(...args);assert.equal(r.code,'OK',`${args[0]}: ${r.status} ${r.code}`);return r.data}
async function check(name,fn,continueOnFailure=false){try{const evidence=await fn();checks.push({name,status:'PASS',evidence});console.log('PASS '+name)}catch(e){checks.push({name,status:'FAIL',error:e.message});if(!continueOnFailure)throw e;process.exitCode=1;console.error('FAIL '+name+': '+e.message)}}
async function session(){const s=await ok('/ai/sessions',driver,{});sessions.push(s.sessionId);return s}
try{
 await check('login and AI permissions',async()=>{
  [admin,driver,other]=await Promise.all(['admin','driver','driver2'].map(async n=>(await ok('/auth/login',null,{username:n,password:process.env['DEMO_'+n.toUpperCase()+'_PASSWORD']})).accessToken))
  assert.equal((await request('/admin/ai-gateway',driver)).status,403)
  assert.equal((await request('/admin/knowledge',driver)).status,403)
  assert.equal((await request('/admin/ai-call-logs',driver)).status,403)
  const state=await ok('/admin/ai-gateway',admin);assert.equal(Number(state.budget.limitMicroyuan),50000000);assert.equal(Number(state.budget.warningMicroyuan),40000000)
 })
 await check('session ownership and persisted history',async()=>{const s=await session();assert.equal((await request(`/ai/sessions/${s.sessionId}/messages`,other)).status,404);const h=await ok(`/ai/sessions/${s.sessionId}/messages`,driver);assert.equal(h.items.length,0);assert.equal(Number(h.sessionVersion),0)})
 await check('knowledge draft publish immutable disable',async()=>{
  const ruleId='ACCEPTANCE_'+Date.now();let row=await ok('/admin/knowledge',admin,{ruleId,communityId:0,ruleVersion:1,title:'验收专用条款',topic:'TEST',keywords:ruleId,content:'仅用于知识生命周期验收。',effectiveAt:'2026-01-01T00:00:00Z'})
  try{row=await ok(`/admin/knowledge/${row.id}/publish`,admin,{version:row.version});assert.equal(row.status,'PUBLISHED');assert.equal((await request(`/admin/knowledge/${row.id}`,admin,{version:row.version,title:'不可覆盖',keywords:ruleId,content:'修改'},'PATCH')).code,'IMMUTABLE_RULE')}
  finally{await ok(`/admin/knowledge/${row.id}/disable`,admin,{version:row.version})}
 })
 if(process.env.AI_ACCEPTANCE_LIVE==='true'){
  await check('publish approved Agnes route',async()=>{
   let state=await ok('/admin/ai-gateway',admin)
   assert.equal(state.budget.paused,false,'Budget is paused; reconcile before live acceptance')
   let provider=state.providers.find(p=>p.baseUrl==='https://apihub.agnes-ai.com/v1'&&p.modelName==='agnes-2.5-flash'&&p.enabled)
   if(!provider)provider=await ok('/admin/ai-gateway/providers',admin,{providerName:'Agnes',protocol:'OPENAI_CHAT',baseUrl:'https://apihub.agnes-ai.com/v1',modelName:'agnes-2.5-flash',credentialRef:'agnes.properties'})
   if(String(state.route.providerRevisionId)!==String(provider.id)){await ok('/admin/ai-gateway/providers/'+provider.id+'/check',admin,{});state=await ok('/admin/ai-gateway/publish',admin,{version:state.route.version,providerRevisionId:Number(provider.id)})}
   return {routeVersion:state.route.version}
  })
  await check('paused budget blocks live admission without upstream usage',async()=>{
   let state=await ok('/admin/ai-gateway',admin);const before=state.budget
   state=await ok('/admin/ai-gateway/budget',admin,{version:before.version,paused:true,freeAccessConfirmed:before.freeAccessConfirmed})
   try{const s=await session(),answer=await ok(`/ai/sessions/${s.sessionId}/messages`,driver,{text:'我想知道提前离开能退回剩下的钱么',expectedSessionVersion:0},'POST',crypto.randomUUID());assert.equal(answer.status,'FALLBACK');assert.equal(answer.fallbackReason,'AI_BUDGET_PAUSED');const after=(await ok('/admin/ai-gateway',admin)).budget;assert.equal(after.spentMicroyuan,before.spentMicroyuan);assert.equal(after.reservedMicroyuan,before.reservedMicroyuan)}
   finally{const current=await ok('/admin/ai-gateway',admin);await ok('/admin/ai-gateway/budget',admin,{version:current.budget.version,paused:before.paused,freeAccessConfirmed:before.freeAccessConfirmed})}
  })
  await check('real rule retrieval, idempotency and history',async()=>{
   const s=await session(),key=crypto.randomUUID(),body={text:'停车计费规则是什么？请查询停车计费规则。',expectedSessionVersion:0}
   const answer=await ok(`/ai/sessions/${s.sessionId}/messages`,driver,body,'POST',key)
   assert.equal(answer.status,'ANSWERED',JSON.stringify(answer));assert.ok(answer.citations?.length)
   assert.deepEqual(await ok(`/ai/sessions/${s.sessionId}/messages`,driver,body,'POST',key),answer)
   const history=await ok(`/ai/sessions/${s.sessionId}/messages`,driver);assert.equal(history.items.length,1);assert.equal(Number(history.sessionVersion),1)
   assert.equal((await request(`/ai/sessions/${s.sessionId}/messages`,driver,{...body,text:'不同内容'},'POST',key)).code,'IDEMPOTENCY_CONFLICT')
   return {requestId:answer.requestId,routeVersion:answer.routeVersion,citations:answer.citations.map(c=>({ruleId:c.ruleId,version:c.version}))}
  },true)
  await check('real space search and owner bill explanation',async()=>{
   const clock=await ok('/demo/clock',driver),communities=await ok('/communities'),vehicles=await ok('/vehicles',driver)
   let start=Math.ceil((Date.parse(clock.now)+3600000)/900000)*900000,space,community
   for(let i=0;i<24&&!space;i++,start+=3600000){for(const c of communities){const found=await ok(`/spaces/search?communityId=${c.id}&startAt=${new Date(start).toISOString()}&endAt=${new Date(start+3600000).toISOString()}`);if(found.items.length){space=found.items[0];community=c;break}}if(space)break}
   assert.ok(space,'No bookable demo window; prepare business windows first')
   const startAt=new Date(start).toISOString(),endAt=new Date(start+3600000).toISOString(),s=await session()
   const found=await ok(`/ai/sessions/${s.sessionId}/messages`,driver,{text:`请查询${community.name}（社区编号${community.id}）从${startAt}到${endAt}的可用车位，不限价格和类型。`,expectedSessionVersion:0},'POST',crypto.randomUUID())
   await check('real space search result',async()=>{assert.equal(found.status,'ANSWERED',JSON.stringify(found));assert.ok(found.cards?.some(c=>String(c.id)===String(space.id)));return {requestId:found.requestId}},true)
   const order=await ok('/reservations',driver,{vehicleId:vehicles[0].id,spaceId:space.id,startAt,endAt},'POST',crypto.randomUUID())
   try{
    let bill;for(let i=0;i<30;i++){const r=await request('/bills/'+order.reservationId,driver);if(r.code==='OK'){bill=r.data;break}await new Promise(r=>setTimeout(r,500))}assert.ok(bill,'Bill projection not ready')
    const bs=await session(),answer=await ok(`/ai/sessions/${bs.sessionId}/messages`,driver,{text:`解释本人预约${order.reservationId}的账单。`,context:{reservationId:order.reservationId},expectedSessionVersion:0},'POST',crypto.randomUUID())
    assert.equal(answer.status,'ANSWERED',JSON.stringify(answer));assert.equal(String(answer.bill.reservationId),String(order.reservationId));assert.equal(answer.bill.prepayFen,bill.prepayFen);assert.ok(answer.citations.length)
    return {searchRequestId:found.requestId,billRequestId:answer.requestId,reservationId:order.reservationId}
   }finally{await ok(`/reservations/${order.reservationId}/cancel`,driver,{})}
  })
 }
 await check('usage accounting is persistent',async()=>{const state=await ok('/admin/ai-gateway',admin);const logs=await ok('/admin/ai-call-logs?size=50',admin);assert.ok(Number(state.budget.spentMicroyuan)>=309);assert.ok(logs.items.length>=1);return {budget:state.budget,callCount:logs.total}})
}catch(e){console.error(e.message);process.exitCode=1}
finally{
 for(const id of sessions){try{await ok(`/ai/sessions/${id}`,driver,undefined,'DELETE')}catch{}}
 const dir=process.env.BUSINESS_REPORT_DIR;await mkdir(dir,{recursive:true});await writeFile(dir+'/results.json',JSON.stringify({at:new Date().toISOString(),liveModel:process.env.AI_ACCEPTANCE_LIVE==='true',checks},null,2))
}
