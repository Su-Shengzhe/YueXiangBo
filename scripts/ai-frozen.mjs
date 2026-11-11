import {checkBoundary,checkBillFacts,waitForExplanation} from './ai-acceptance-checks.mjs'
import {verdict,writeJson} from './ai-frozen-verdict.mjs'
import assert from 'node:assert/strict'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {spawnSync} from 'node:child_process'
import {createHash} from 'node:crypto'
const dir=process.env.BUSINESS_REPORT_DIR,base='http://127.0.0.1:9090/api/v1',results=[],tokenAliases=new Map(),tokenOwners=new Map()
await mkdir(dir,{recursive:true})
const billRaceOnly=process.argv.includes('--bill-race-only')
let hash=null
function checkpoint(error){
 writeJson(dir+'/results.json',{hash,results})
 const summary=verdict(results,billRaceOnly,hash,error)
 writeJson(dir+'/summary.json',summary)
 return summary
}
function finish(error){
 const summary=checkpoint(error)
 console.log(JSON.stringify(summary))
 process.exitCode=summary.passed?0:1
}
for(const signal of ['SIGINT','SIGTERM']){
 process.once(signal,()=>{finish(signal);process.exit(1)})
}
async function run(){
checkpoint('RUNNING')
const sampleIndex=process.argv.indexOf('--results-file')
if(sampleIndex>=0){
 const sample=JSON.parse(await readFile(process.argv[sampleIndex+1],'utf8'))
 assert.ok(Array.isArray(sample.results),'Missing results array')
 results.push(...sample.results)
 hash=sample.hash??null
 return
}
async function api(path,token,body,key=crypto.randomUUID(),method=body===undefined?'GET':'POST',renewed=false){
 const r=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+(tokenAliases.get(token)||token)}:{}),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':key})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)});const j=await r.json();
 if(j.code==='TOKEN_EXPIRED'&&!renewed&&tokenOwners.has(token)){tokenAliases.set(token,await login(tokenOwners.get(token)));return api(path,token,body,key,method,true)}
 assert.equal(j.code,'OK',path+':'+j.code);return j.data
}
async function login(name){return (await api('/auth/login',null,{username:name,password:process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']})).accessToken}
const [admin,driver]=await Promise.all(['admin','driver'].map(login)),initial=await api('/admin/ai-gateway',admin),clock=await api('/demo/clock',driver)
tokenOwners.set(admin,'admin');tokenOwners.set(driver,'driver')
const allCases=JSON.parse(await readFile('docs/acceptance/ai-m4-cases.json','utf8')),oracles=JSON.parse(await readFile('docs/acceptance/ai-bill-oracles.json','utf8')).scenarios
const inputs=JSON.parse(await readFile('docs/acceptance/ai-bill-inputs.json','utf8'))
const cases=billRaceOnly?allCases.filter(c=>c.category==='bill'&&c.scenario==='解释期间版本变化'):allCases
const prefix=800000000000+Math.floor(Date.now()/1000)*100,community=prefix,space=prefix+1,window=prefix+2
const anchor=Math.ceil((Date.parse(clock.now)+86400000)/900000)*900000,iso=t=>new Date(t).toISOString(),sqltime=t=>iso(t).slice(0,19).replace('T',' ')
const quote=x=>"'"+String(x).replaceAll("'","''")+"'"
const rules={BILLING:'预约费与超时费分别按30分钟单位向上取整，提前离场不退预约费。无免费宽限、优惠券和跨日封顶。价格以订单下单快照为准。',CANCEL:'司机可在预约开始前取消。未付款订单关闭并释放；已付款订单申请全额可退余额退款。取消不等于退款成功，退款结果另行展示。',REFUND:'用户可在订单完成或关闭后7天内提出费用争议，达到截止即不受理。驳回后在期限内补充新证据并关联原申请。结果未知的退款不算成功退款。',ARRIVAL:'仅已确认订单可入场，允许在预约开始至开始后15分钟之前入场。达到截止仍未入场则按原因关闭并申请退款，退款状态单独展示。',BOOKING:'预约起止按15分钟对齐，时长30分钟至24小时，开始时间至少晚于当前15分钟，且须被同一个共享窗口覆盖。查询结果不锁定库存，提交时重新校验。'}
const sql=[`INSERT INTO parking_db.community(id,name) VALUES(${community},'冻结验收社区${community}')`,`INSERT INTO parking_db.parking_space(id,community_id,owner_id,name,space_code,hourly_fen,review_status) VALUES(${space},${community},3,'验收车位','EVAL',500,'APPROVED')`,`INSERT INTO parking_db.share_window(id,space_id,start_at,end_at,hourly_fen) VALUES(${window},${space},${quote(sqltime(anchor))},${quote(sqltime(anchor+7*86400000))},500)`]
for(const c of cases){
 if(c.category==='query'){c.startAt=iso(anchor+c.offset*900000);c.endAt=iso(Date.parse(c.startAt)+c.duration*60000);c.prompt=c.prompt.replaceAll('{community}',`冻结验收社区${community}（编号${community}）`).replaceAll('{start}',c.startAt).replaceAll('{end}',c.endAt);c.expectedCards=c.price!==null&&c.price<500?[]:[{id:String(space),hourlyFen:500,estimatedFen:Math.ceil(c.duration/30)*250}];}
 if(c.category==='rule'){c.expectedClause=rules[c.rule];assert.ok(c.expectedClause)}
 if(c.category==='bill'){
  delete c.blocked;const oracle=oracles.find(x=>x.name===c.scenario);assert.ok(oracle);c.expected=oracle;c.reservationId=String(prefix+100+Number(c.id.slice(1)));c.prompt=c.variation===1?'解释本人账单':'查看本人账单，说明收款、退款与剩余实收';
  const f=inputs[c.scenario];assert.ok(f);c.input=f
  const {state,overtime:over}=f,id=c.reservationId
  const snapshot=!f.ruleSnapshot?'NULL':quote(JSON.stringify({ruleId:'BILLING',version:1,communityId:'0',title:'下单计费规则',clause:'预约费与超时费分别按30分钟单位向上取整；提前离场不退预约费，无免费宽限。',unitMinutes:30}));
  sql.push(`INSERT INTO parking_db.reservation_order(id,user_id,vehicle_id,space_id,community_id,plate,hourly_fen,start_at,end_at,pay_deadline,prepay_fen,overtime_fen,status,version,rule_snapshot,owner_id) VALUES(${id},1,40,${space},${community},'EVAL',500,${quote(sqltime(anchor+8*86400000))},${quote(sqltime(anchor+8*86400000+3600000))},'2099-01-01',${f.prepay},${over},'${state}',1,${snapshot},3)`);
  sql.push(`INSERT INTO trade_db.trade_order(reservation_id,user_id,prepay_fen,overtime_fen,paid_prepay_fen,paid_overtime_fen,refund_fen,status,version,bill_version,owner_id,settlement_status) VALUES(${id},1,${f.prepay},${over},${f.receipts.prepay},${f.receipts.overtime},${f.refunds.succeeded},'${state}',1,1,3,'${f.settlement}')`);
  if(f.refunds.unknown)sql.push(`INSERT INTO trade_db.payment_attempt(id,reservation_id,user_id,kind,amount_fen,status,channel_no,next_retry_at,lease_until) VALUES(${prefix+300+Number(c.id.slice(1))},${id},1,'REFUND',${f.refunds.unknown},'UNKNOWN','EVAL-${id}','2099-01-01','2099-01-01')`);
 }
}
assert.equal(allCases.length,100)
assert.equal(cases.length,billRaceOnly?2:100)
await writeFile(dir+'/fixtures.sql',sql.join(';\n')+';')
const seed=spawnSync('java',['-cp',process.env.EVAL_JAVA_CP,'scripts/EvalSql.java',dir+'/fixtures.sql'],{encoding:'utf8'});await writeFile(dir+'/fixture-load.log',seed.stdout+seed.stderr);assert.equal(seed.status,0,'Fixture load failed')
const frozen={mode:'REAL_HTTP_REAL_MODEL_SYNTHETIC_FROZEN_BUSINESS_STATES',route:initial.route,clock:clock.now,communityId:String(community),cases,rules,createdAt:new Date().toISOString(),build:JSON.parse(await readFile('.runtime/backend-build.json','utf8')).id}
const bytes=JSON.stringify(frozen,null,2);hash=createHash('sha256').update(bytes).digest('hex');await writeFile(dir+'/frozen.json',bytes);await writeFile(dir+'/frozen.sha256',hash)
const delay=ms=>new Promise(r=>setTimeout(r,ms));let next=0
for(const c of cases){let sid,answer,race,priorCall,grading=false;try{
 await delay(Math.max(0,next-Date.now()));priorCall=(await api('/admin/ai-call-logs?size=1',admin)).items[0]?.requestId;next=Date.now()+12000
 assert.equal(createHash('sha256').update(await readFile(dir+'/frozen.json')).digest('hex'),hash)
 assert.equal((await api('/admin/ai-gateway',admin)).route.version,initial.route.version,'Route changed')
 sid=(await api('/ai/sessions',driver,{})).sessionId;const body={text:c.prompt,expectedSessionVersion:0,...(c.category==='bill'?{context:{reservationId:c.reservationId}}:{})}
 if(c.scenario==='解释期间版本变化'){
  const key=crypto.randomUUID(),before=await api('/bills/'+c.reservationId,driver)
  const pending=api(`/ai/sessions/${sid}/messages`,driver,body,key)
  pending.catch(()=>{})
  try{
   race=await waitForExplanation(()=>api(`/ai/sessions/${sid}/messages`,driver),()=>api('/admin/ai-call-logs?size=100',admin),key)
   race.beforeVersion=before.billVersion
   race.paymentStartedAt=new Date().toISOString()
   await api('/payments',driver,{reservationId:c.reservationId,kind:'PREPAY'})
   answer=await pending
   const history=await api(`/ai/sessions/${sid}/messages`,driver)
   race.finishedAt=history.items.find(m=>m.requestId===race.messageId)?.finishedAt
   grading=true
   assert.equal(answer.requestId,race.messageId)
   assert.ok(Date.parse(race.finishedAt)>Date.parse(race.paymentStartedAt))
  }finally{await pending.catch(()=>{})}
 }else answer=await api(`/ai/sessions/${sid}/messages`,driver,body)
 grading=true
 if(c.category==='query'){assert.equal(answer.status,'ANSWERED');assert.deepEqual((answer.cards||[]).map(x=>({id:x.id,hourlyFen:x.hourlyFen,estimatedFen:x.estimatedFen})),c.expectedCards)}
 if(c.category==='rule'){assert.equal(answer.status,'ANSWERED');assert.ok(answer.citations.some(x=>x.ruleId===c.rule&&x.version===1&&x.clause===c.expectedClause))}
 if(c.category==='boundary')checkBoundary(c,answer)
 if(c.category==='bill'){assert.equal(answer.status,c.expected.status);if(c.expected.fallbackReason)assert.equal(answer.fallbackReason,c.expected.fallbackReason);checkBillFacts(c.expected.facts,answer.bill);if(c.scenario==='解释期间版本变化'){assert.ok(answer.bill.billVersion>race.beforeVersion);assert.deepEqual(answer.explanations,[]);assert.deepEqual(answer.reasons,[])}}
 results.push({id:c.id,category:c.category,status:'PASS',answer,race})
 }catch(e){results.push({id:c.id,category:c.category,status:'FAIL',reason:e.message,answer,race});if(!grading || e.code!=='ERR_ASSERTION')throw e}finally{
  checkpoint('RUNNING')
  if(sid)await api(`/ai/sessions/${sid}`,driver,undefined,undefined,'DELETE').catch(()=>{})
  if(priorCall && (await api('/admin/ai-call-logs?size=1',admin).catch(()=>({items:[]}))).items[0]?.requestId===priorCall)next=0
  console.log(`${results.length}/${cases.length} ${c.id} ${results.at(-1).status}`)
 }}
}

try{
 await run()
 finish()
}catch(error){
 finish(error.message || 'Evaluation failed')
}
