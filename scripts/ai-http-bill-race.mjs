import {waitForExplanation} from './ai-acceptance-checks.mjs'
import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
const base='http://127.0.0.1:9090/api/v1',evidence={status:'RUNNING',mode:'REAL_HTTP_REAL_PROVIDER_SIMULATED_PAYMENT'}
async function call(path,token,body,key=crypto.randomUUID(),method=body===undefined?'GET':'POST'){
 const r=await fetch(base+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json','Idempotency-Key':key})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)});const result=await r.json();assert.equal(result.code,'OK',path+': '+result.code);return result.data
}
async function login(name){return (await call('/auth/login',null,{username:name,password:process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']})).accessToken}
const [driver,admin]=await Promise.all(['driver','admin'].map(login));let reservation,session,inFlight,noiseSession,noiseFlight
const pause=ms=>new Promise(r=>setTimeout(r,ms))
async function until(action,test){for(let i=0;i<120;i++){const r=await action();if(test(r))return r;await pause(50)}throw Error('Expected state was not observed')}
try{
 const clock=await call('/demo/clock',driver);let start=Math.ceil((Date.parse(clock.now)+3600000)/900000)*900000
 for(let i=0;i<24;i++,start+=3600000){const startAt=new Date(start).toISOString(),endAt=new Date(start+3600000).toISOString();const spaces=await call(`/spaces/available?communityId=10&startAt=${startAt}&endAt=${endAt}`);if(spaces.some(x=>x.id==='20')){reservation=(await call('/reservations',driver,{vehicleId:'40',spaceId:'20',startAt,endAt})).reservationId;break}}
 assert.ok(reservation,'A free seeded window is required')
 const before=await until(()=>call('/bills/'+reservation,driver).catch(()=>({})),b=>b.billVersion!==undefined)
 session=(await call('/ai/sessions',driver,{})).sessionId;const key=crypto.randomUUID(),input={text:'解释本人账单',expectedSessionVersion:0,context:{reservationId:reservation}}
 const logs=()=>call('/admin/ai-call-logs?size=100',admin)
 const historyFor=id=>()=>call(`/ai/sessions/${id}/messages`,driver)
 noiseSession=(await call('/ai/sessions',driver,{})).sessionId
 const noiseKey=crypto.randomUUID()
 const targetAdmission=waitForExplanation(historyFor(session),logs,key,28000)
 targetAdmission.catch(()=>{})
 noiseFlight=call(`/ai/sessions/${noiseSession}/messages`,driver,input,noiseKey)
 noiseFlight.catch(()=>{})
 evidence.interferingCall=await waitForExplanation(historyFor(noiseSession),logs,noiseKey)
 const noiseAnswer=await noiseFlight
 assert.equal(noiseAnswer.status,'ANSWERED')
 assert.equal(noiseAnswer.requestId,evidence.interferingCall.messageId)
 inFlight=call(`/ai/sessions/${session}/messages`,driver,input,key)
 inFlight.catch(()=>{})
 evidence.admission=await targetAdmission
 assert.notEqual(evidence.admission.messageId,evidence.interferingCall.messageId)
 // The explicit bill context reads facts before admitting its sole explanation call.
 evidence.paymentStartedAt=new Date().toISOString();evidence.payment=await call('/payments',driver,{reservationId:reservation,kind:'PREPAY'});evidence.paymentAcceptedAt=new Date().toISOString()
 const answer=await inFlight;assert.equal(answer.requestId,evidence.admission.messageId);evidence.answer=answer;evidence.before=before
 assert.equal(answer.status,'FALLBACK');assert.equal(answer.fallbackReason,'BILL_CHANGED');assert.ok(answer.bill.billVersion>before.billVersion);assert.deepEqual(answer.explanations,[]);assert.deepEqual(answer.reasons,[])
 const history=await call(`/ai/sessions/${session}/messages`,driver);evidence.message=history.items.find(x=>x.requestId===answer.requestId)
 assert.ok(Date.parse(evidence.message.createdAt)<Date.parse(evidence.paymentStartedAt));assert.ok(Date.parse(evidence.message.finishedAt)>Date.parse(evidence.paymentStartedAt))
 const countBefore=(await call('/admin/ai-call-logs?size=100',admin)).total
 assert.deepEqual(await call(`/ai/sessions/${session}/messages`,driver,input,key),answer)
 assert.equal((await call('/admin/ai-call-logs?size=100',admin)).total,countBefore)
 // Wait until both payment and parking projection settle before the unchanged control.
 await until(async()=>({bill:await call('/bills/'+reservation,driver),order:await call('/reservations/'+reservation,driver)}),r=>r.bill.paidPrepayFen===500&&r.order.status==='CONFIRMED'&&r.bill.reservationVersion===r.order.version)
 const control=await call(`/ai/sessions/${session}/messages`,driver,{...input,expectedSessionVersion:answer.sessionVersion})
 evidence.control=control;assert.equal(control.status,'ANSWERED');assert.equal(control.bill.paidFen,500);assert.ok(control.explanations.length>0)
 evidence.status='PASS';console.log('PASS real-http-bill-version-race-and-idempotent-replay')
}catch(e){evidence.status='FAIL';evidence.error=e.message;process.exitCode=1}
finally{
 if(inFlight)await inFlight.catch(()=>{})
 if(noiseFlight)await noiseFlight.catch(()=>{})
 if(noiseSession)await call(`/ai/sessions/${noiseSession}`,driver,undefined,undefined,'DELETE').catch(e=>{evidence.noiseCleanup=e.message})
 if(session)await call(`/ai/sessions/${session}`,driver,undefined,undefined,'DELETE').catch(e=>{evidence.sessionCleanup=e.message})
 if(reservation)await call(`/reservations/${reservation}/cancel`,driver,{}).catch(e=>{evidence.orderCleanup=e.message})
 evidence.reservationId=reservation;await mkdir(process.env.BUSINESS_REPORT_DIR,{recursive:true});await writeFile(process.env.BUSINESS_REPORT_DIR+'/results.json',JSON.stringify(evidence,null,2));console.log(evidence.status,evidence.error||'')
}
