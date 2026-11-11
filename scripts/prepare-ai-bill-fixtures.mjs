import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
const base='http://127.0.0.1:9090/api/v1',records=[],orders=[]
async function call(path,token,body){const r=await fetch(base+path,{method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':crypto.randomUUID()})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)});const result=await r.json();assert.equal(result.code,'OK',path+': '+result.code);return result.data}
async function login(name){return (await call('/auth/login',null,{username:name,password:process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']})).accessToken}
const [driver,admin,property]=await Promise.all(['driver','admin','property'].map(login))
const iso=ms=>new Date(ms).toISOString()
async function until(path,token,test){for(let n=0;n<50;n++){try{const value=await call(path,token);if(test(value))return value}catch(e){if(n===49)throw e}await new Promise(r=>setTimeout(r,400))}throw Error('Projection timeout '+path)}
async function snapshot(name,id,expected){const order=await call('/reservations/'+id,driver);const bill=await until('/bills/'+id,driver,b=>b.reservationVersion===order.version&&Object.entries(expected).every(([k,v])=>b[k]===v));assert.equal(bill.reservationVersion,order.version);assert.ok(order.ruleSnapshot);records.push({name,reservationId:id,expected,bill,order,capturedAt:new Date().toISOString(),kind:'REAL_DEMO_BUSINESS_SNAPSHOT'});console.log('PASS '+name)}
async function reserve(minutes){const clock=await call('/demo/clock',driver);let start=Math.ceil((Date.parse(clock.now)+3600000)/900000)*900000;for(let i=0;i<24;i++,start+=3600000){const end=start+minutes*60000;const spaces=await call(`/spaces/available?communityId=10&startAt=${iso(start)}&endAt=${iso(end)}`);if(spaces.some(x=>x.id==='20')){const order=await call('/reservations',driver,{vehicleId:'40',spaceId:'20',startAt:iso(start),endAt:iso(end)});orders.push(order.reservationId);return {id:order.reservationId,start,end}}}throw Error('No seeded 500-fen/hour window available')}
let active
try{
 const one=await reserve(60);active=one.id
 await snapshot('UNPAID',one.id,{prepayFen:500,paidPrepayFen:0,overtimeFen:0,refundFen:0})
 await call('/payments',driver,{reservationId:one.id,kind:'PREPAY'});await until('/reservations/'+one.id,driver,o=>o.status==='CONFIRMED')
 await snapshot('PREPAID',one.id,{prepayFen:500,paidPrepayFen:500,paidOvertimeFen:0,refundFen:0})
 await call('/demo/clock',admin,{now:iso(one.start)});await call(`/property/reservations/${one.id}/entry`,property,{})
 await call('/demo/clock',admin,{now:iso(one.end+1000)});await call(`/property/reservations/${one.id}/exit`,property,{})
 await snapshot('OVERTIME_UNPAID',one.id,{status:'COMPLETED',prepayFen:500,overtimeFen:250,paidPrepayFen:500,paidOvertimeFen:0,refundFen:0})
 await call('/payments',driver,{reservationId:one.id,kind:'OVERTIME'})
 await snapshot('OVERTIME_PAID',one.id,{status:'COMPLETED',prepayFen:500,overtimeFen:250,paidPrepayFen:500,paidOvertimeFen:250,refundFen:0});active=null
 const partial=await call('/refund-applications',driver,{reservationId:one.id,amountFen:200,reason:'验收：结算前部分退款',evidence:'真实演示业务流程'})
 await call(`/refund-applications/${partial.id}/decision`,admin,{decision:'APPROVE',note:'核对750分实收，批准退200分'})
 await snapshot('PARTIAL_REFUND',one.id,{refundFen:200,settlementStatus:'UNSETTLED'})
 const preview=await call('/admin/settlements/preview',admin,{reservationIds:[one.id]});assert.deepEqual(preview.items[0].blockers,[]);assert.equal(preview.items[0].requestedFen,495)
 const batch=await call('/admin/settlement-batches',admin,{reservationIds:[one.id]})
 await snapshot('SETTLED',one.id,{refundFen:200,settlementStatus:'SETTLED'})
 const settled=await call(`/admin/settlement-batches/${batch.batchId}`,admin);assert.equal(settled.items[0].requestedFen,495);assert.equal(settled.items[0].platformAmountFen,55)
 const after=await call('/refund-applications',driver,{reservationId:one.id,amountFen:100,reason:'验收：结算后退款',evidence:'保留原结算，追加待追回'})
 await call(`/refund-applications/${after.id}/decision`,admin,{decision:'APPROVE',note:'核对剩余550分，批准退100分'})
 await snapshot('POST_SETTLEMENT_REFUND',one.id,{refundFen:300,settlementStatus:'SETTLED'})
 const adjusted=await call(`/admin/settlement-batches/${batch.batchId}`,admin);assert.equal(adjusted.items[0].requestedFen,495);assert.equal(adjusted.items[0].adjustments[0].ownerRecoveryFen,90);assert.equal(adjusted.items[0].adjustments[0].platformReversalFen,10);assert.equal(adjusted.items[0].adjustments[0].status,'PENDING_RECOVERY')
 records.push({name:'SETTLEMENT_AUDIT',batchId:batch.batchId,expected:{ownerPayoutFen:495,ownerRecoveryFen:90,platformReversalFen:10},batch:adjusted})
 const two=await reserve(30);active=two.id;await until('/bills/'+two.id,driver,b=>b.prepayFen===250);await call('/payments',driver,{reservationId:two.id,kind:'PREPAY'});await until('/reservations/'+two.id,driver,o=>o.status==='CONFIRMED');await call(`/reservations/${two.id}/cancel`,driver,{})
 await snapshot('CANCEL_REFUNDED',two.id,{status:'CANCELLED',prepayFen:250,paidPrepayFen:250,refundFen:250});active=null
 const three=await reserve(30);active=three.id;await call(`/reservations/${three.id}/cancel`,driver,{})
 await snapshot('UNPAID_CANCELLED',three.id,{status:'CANCELLED',prepayFen:250,paidPrepayFen:0,refundFen:0});active=null
}finally{
 if(active)try{await call(`/reservations/${active}/cancel`,driver,{})}catch{console.error('Cleanup requires checking reservation '+active)}
 const dir=process.env.BUSINESS_REPORT_DIR;await mkdir(dir,{recursive:true});await writeFile(dir+'/bill-fixtures.json',JSON.stringify({records,orders,limitations:['Captured intermediate states are replay fixtures, not current live orders','Demo clock advances; do not run concurrently with evaluations','Unknown and version-race fixtures require separate controlled fault tests']},null,2))
}
