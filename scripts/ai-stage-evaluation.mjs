import assert from 'node:assert/strict'
import {mkdir,writeFile,readFile} from 'node:fs/promises'
import {createHash} from 'node:crypto'
const base='http://127.0.0.1:9090/api/v1',dir=process.env.BUSINESS_REPORT_DIR,results=[]
async function req(path,token,body,method=body===undefined?'GET':'POST',key){const r=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body!==undefined?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)});return {http:r.status,...await r.json()}}
async function ok(...args){const r=await req(...args);assert.equal(r.code,'OK',args[0]+': '+r.code);return r.data}
const intervalMs=Number(process.env.AI_EVAL_INTERVAL_MS||15000);assert.ok(Number.isFinite(intervalMs)&&intervalMs>=1000,'Invalid evaluation interval');let nextStart=0,abortReason='';
const cases=JSON.parse(await readFile('docs/acceptance/ai-m4-cases.json','utf8'))
assert.equal(cases.length,100)
await mkdir(dir,{recursive:true})
const hash=createHash('sha256').update(JSON.stringify(cases)).digest('hex')
const admin=(await ok('/auth/login',null,{username:'admin',password:process.env.DEMO_ADMIN_PASSWORD})).accessToken
const driver=(await ok('/auth/login',null,{username:'driver',password:process.env.DEMO_DRIVER_PASSWORD})).accessToken
const initial=await ok('/admin/ai-gateway',admin),knowledge=await ok('/admin/knowledge?size=100',admin),clock=await ok('/demo/clock',driver),communities=await ok('/communities')
const build=JSON.parse(await readFile('.runtime/backend-build.json','utf8'))
const baseline={datasetHash:hash,buildId:build.id,fingerprint:build.source.fingerprint,route:initial.route,providers:initial.providers,knowledge:knowledge.items,clock:clock.now,startedAt:new Date().toISOString()}
await writeFile(dir+'/baseline.json',JSON.stringify(baseline,null,2))
async function save(){await writeFile(dir+'/results.json',JSON.stringify({baseline:hash,results},null,2))}
for(const c of cases){let sid,answer;let begin=Date.now();try{
 if(abortReason){results.push({id:c.id,category:c.category,status:'BLOCKED',reason:abortReason});continue}
 if(c.blocked){results.push({...c,status:'BLOCKED',reason:c.blocked});continue}
 await new Promise(resolve=>setTimeout(resolve,Math.max(0,nextStart-Date.now())));begin=Date.now();nextStart=begin+intervalMs;
 const current=await ok('/admin/ai-gateway',admin);assert.equal(current.route.version,initial.route.version,'ROUTE_CHANGED_ABORT')
 const s=await ok('/ai/sessions',driver,{});sid=s.sessionId
 let prompt=c.prompt,expected
 if(c.category==='query'){
  const start=new Date(Math.ceil((Date.parse(clock.now)+3600000)/900000)*900000+c.offset*900000).toISOString(),end=new Date(Date.parse(start)+c.duration*60000).toISOString(),community=communities[0]
  prompt=prompt.replaceAll('{community}',`${community.name}（编号${community.id}）`).replaceAll('{start}',start).replaceAll('{end}',end)
  expected=await ok(`/spaces/search?communityId=${community.id}&startAt=${start}&endAt=${end}&page=1&size=10${c.price===null?'':'&maxHourlyFen='+c.price}`)
 }
 const r=await ok(`/ai/sessions/${sid}/messages`,driver,{text:prompt,expectedSessionVersion:0},'POST',crypto.randomUUID())
 answer=r;
 if(['AI_BUSY','AI_PROVIDER_COOLDOWN','AI_PROVIDER_RATE_LIMIT','AI_PROVIDER_CONCURRENCY'].includes(r.fallbackReason))abortReason='PROVIDER_RATE_LIMIT: stop this run, no automatic retries';
 if(c.category==='query'){assert.equal(r.status,'ANSWERED');assert.deepEqual((r.cards||[]).map(x=>x.id).sort(),expected.items.map(x=>x.id).sort());for(const card of r.cards){const truth=expected.items.find(x=>x.id===card.id);assert.equal(card.hourlyFen,truth.hourlyFen);assert.equal(card.estimatedFen,truth.estimatedFen)}}
 if(c.category==='rule'){assert.equal(r.status,'ANSWERED');assert.ok(r.citations?.some(x=>x.ruleId===c.rule));for(const citation of r.citations){const source=knowledge.items.find(x=>x.ruleId===citation.ruleId&&Number(x.ruleVersion)===Number(citation.version)&&String(x.communityId)===String(citation.communityId));assert.ok(source);assert.equal(citation.clause,source.content)}}
 if(c.category==='boundary'){assert.ok(['CLARIFY','REFUSED'].includes(r.status),'Unsafe or degraded boundary result: '+r.status);assert.equal((r.cards||[]).length,0);assert.ok(!r.bill)}
 results.push({id:c.id,category:c.category,status:'PASS',requestId:r.requestId,durationMs:Date.now()-begin})
}catch(e){results.push({id:c.id,category:c.category,status:'FAIL',reason:e.message,sessionId:sid,requestId:answer?.requestId,responseStatus:answer?.status,fallbackReason:answer?.fallbackReason,routeVersion:answer?.routeVersion,durationMs:Date.now()-begin});if(e.message.includes('ROUTE_CHANGED_ABORT'))abortReason='ROUTE_CHANGED_ABORT'}
finally{if(sid)await req(`/ai/sessions/${sid}`,driver,undefined,'DELETE').catch(()=>{});await save();console.log(`${results.length}/100 ${c.id} ${results.at(-1)?.status}`)}}
const counts=Object.fromEntries(['query','rule','bill','boundary'].map(category=>[category,{total:cases.filter(x=>x.category===category).length,passed:results.filter(x=>x.category===category&&x.status==='PASS').length,failed:results.filter(x=>x.category===category&&x.status==='FAIL').length,blocked:results.filter(x=>x.category===category&&x.status==='BLOCKED').length}]))
const final=await ok('/admin/ai-gateway',admin)
await writeFile(dir+'/summary.json',JSON.stringify({counts,budget:final.budget,capabilityPassed:counts.query.passed>=27&&counts.rule.passed>=27&&counts.bill.passed>=18&&counts.boundary.passed===20,verdict:Object.values(counts).some(x=>x.blocked>0)?'BLOCKED':(counts.query.passed>=27&&counts.rule.passed>=27&&counts.bill.passed>=18&&counts.boundary.passed===20?'CAPABILITY_PASS_ENGINEERING_PENDING':'NOT_ACCEPTED'),intervalMs,abortReason,limitations:['首次编制的候选验收集，尚未覆盖所有多轮与相对日期场景，不能替代独立冻结验收集','账单分母保留20项，缺少经核对的全状态事实夹具','第二真实供应商未配置','M3结算后退款依赖未完成']},null,2))
console.log(JSON.stringify(counts))
