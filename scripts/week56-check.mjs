import assert from 'node:assert/strict'
import {mkdir,writeFile} from 'node:fs/promises'
const base='http://127.0.0.1:9090/api/v1',results=[],suffix=Date.now().toString(36),password=crypto.randomUUID()+'Aa9',dir=process.env.WEEK56_REPORT_DIR
const key=()=>crypto.randomUUID(),iso=n=>new Date(n).toISOString()
async function request(path,token,body,method,keyValue){const headers={};if(token)headers.Authorization='Bearer '+token;if(body!==undefined)headers['Content-Type']='application/json';if(keyValue)headers['Idempotency-Key']=keyValue;const res=await fetch(base+path,{method:method||(body===undefined?'GET':'POST'),headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(26000)});return {status:res.status,...await res.json()}}
async function ok(path,token,body,method,keyValue){const r=await request(path,token,body,method,keyValue);assert.equal(r.code,'OK',path+': '+r.code+' '+r.message);return r.data}
async function login(username,pass=password){return await ok('/auth/login',null,{username,password:pass})}
async function check(name,fn){const started=Date.now();try{await fn();results.push({name,status:'PASS',durationMs:Date.now()-started});console.log('PASS '+name)}catch(e){results.push({name,status:'FAIL',error:e.message});throw e}}
async function until(fn,predicate){for(let i=0;i<60;i++){const v=await fn();if(predicate(v))return v;await new Promise(r=>setTimeout(r,500))}throw Error('Asynchronous operation timed out')}
let admin,owner,other,propertyA,propertyB,c1,c2,vehicle,space,window,quote,start,end,reservation
const users={}
try{
 await check('registration cannot self-authorize; default driver and multi-role session revocation',async()=>{
  admin=(await login('admin',process.env.DEMO_ADMIN_PASSWORD)).accessToken
  assert.equal((await request('/auth/register',null,{username:'bad_'+suffix,password,roles:['PLATFORM']})).status,422)
  for(const name of ['owner','other','propertya','propertyb'])users[name]=await ok('/auth/register',null,{username:name+'_'+suffix,password})
  owner=await login('owner_'+suffix);assert.deepEqual(owner.user.roles,['DRIVER'])
  await ok('/admin/users/'+users.owner.id+'/roles',admin,{roles:['DRIVER','OWNER']},'PUT')
  assert.equal((await request('/auth/me',owner.accessToken)).status,401)
  owner=await login('owner_'+suffix);assert.deepEqual(owner.user.roles,['DRIVER','OWNER'])
  other=await login('other_'+suffix)
  assert.equal((await request('/admin/users/'+users.owner.id+'/roles',other.accessToken,{roles:['PLATFORM']},'PUT')).status,403)
  assert.equal((await request('/admin/users/5/status',admin,{enabled:false},'PATCH')).code,'LAST_ADMIN')
 })
 await check('communities and property scope; revoked sessions after reassignment',async()=>{
  c1=await ok('/communities',admin,{name:'验收A_'+suffix,address:'虚拟社区A'})
  c2=await ok('/communities',admin,{name:'验收B_'+suffix,address:'虚拟社区B'})
  for(const name of ['propertya','propertyb'])await ok('/admin/users/'+users[name].id+'/roles',admin,{roles:['PROPERTY']},'PUT')
  propertyA=await login('propertya_'+suffix)
  await ok('/admin/communities/'+c1.id+'/administrators',admin,{userIds:[users.propertya.id]},'PUT')
  await ok('/admin/communities/'+c2.id+'/administrators',admin,{userIds:[users.propertyb.id]},'PUT')
  assert.equal((await request('/auth/me',propertyA.accessToken)).status,401)
  propertyA=await login('propertya_'+suffix);propertyB=await login('propertyb_'+suffix)
  assert.equal((await request('/property/spaces?communityId='+c1.id,propertyB.accessToken)).status,403)
 })
 await check('vehicle normalization, uniqueness and ownership',async()=>{
  const plate='粤B'+Math.floor(Math.random()*90000+10000)
  vehicle=await ok('/vehicles',owner.accessToken,{plate:plate.toLowerCase()})
  assert.equal(vehicle.plate,plate)
  assert.equal((await request('/vehicles',other.accessToken,{plate})).status,409)
  assert.equal((await request('/vehicles/'+vehicle.id,other.accessToken,{version:1,enabled:false},'PATCH')).status,404)
 })
 await check('draft, reject, resubmit, approve; stale review rejected',async()=>{
  space=await ok('/spaces',owner.accessToken,{communityId:c1.id,spaceCode:'A-'+suffix,locationDesc:'一层A区',spaceType:'STANDARD',proofText:'PRIVATE_PROOF_'+suffix})
  space=await ok('/spaces/'+space.id+'/submit',owner.accessToken,{version:space.version})
  assert.equal((await request('/spaces/'+space.id+'/reviews',propertyB.accessToken,{version:space.version,decision:'APPROVE',comment:'越权'})).status,403)
  const old=space.version;space=await ok('/spaces/'+space.id+'/reviews',propertyA.accessToken,{version:space.version,decision:'REJECT',comment:'补充说明后重提'})
  assert.equal((await request('/spaces/'+space.id+'/reviews',propertyA.accessToken,{version:old,decision:'APPROVE',comment:'旧请求'})).code,'STALE_VERSION')
  space=await ok('/spaces/'+space.id,owner.accessToken,{version:space.version,spaceCode:'A-'+suffix,locationDesc:'一层A区',spaceType:'STANDARD',proofText:'PRIVATE_PROOF_'+suffix},'PATCH')
  space=await ok('/spaces/'+space.id+'/submit',owner.accessToken,{version:space.version})
  space=await ok('/spaces/'+space.id+'/reviews',propertyA.accessToken,{version:space.version,decision:'APPROVE',comment:'资料齐全'})
  assert.equal(space.reviewStatus,'APPROVED')
 })
 await check('window validation, adjacent intervals, concurrent overlap and public whitelist',async()=>{
  const now=(await ok('/demo/clock',owner.accessToken)).now;start=Math.ceil((Date.parse(now)+3600000)/900000)*900000;end=start+7200000
  const body={startAt:iso(start),endAt:iso(end+3600000),hourlyFen:400}
  const attempts=await Promise.all([request('/spaces/'+space.id+'/windows',owner.accessToken,body),request('/spaces/'+space.id+'/windows',owner.accessToken,body)])
  assert.deepEqual(attempts.map(r=>r.status).sort(),[200,409]);window=attempts.find(r=>r.status===200).data
  await ok('/spaces/'+space.id+'/windows',owner.accessToken,{startAt:iso(end+3600000),endAt:iso(end+7200000),hourlyFen:500})
  assert.equal((await request('/spaces/'+space.id+'/windows',owner.accessToken,{startAt:iso(start+1),endAt:iso(end),hourlyFen:400})).status,422)
  const data=await ok('/spaces/search?'+new URLSearchParams({communityId:c1.id,startAt:iso(start),endAt:iso(end),spaceType:'STANDARD',maxHourlyFen:'400'}))
  assert.equal(data.total,1);quote=data.items[0];assert.equal(quote.estimatedFen,800)
  assert.equal(JSON.stringify(data).includes('PRIVATE_PROOF'),false);assert.equal('ownerId' in quote,false)
 })
 await check('quote change requires confirmation; reservation protects resources',async()=>{
  window=await ok('/windows/'+window.id,owner.accessToken,{version:window.version,startAt:iso(start),endAt:iso(end+3600000),hourlyFen:600},'PATCH')
  const body={vehicleId:vehicle.id,spaceId:space.id,windowId:quote.windowId,windowVersion:quote.windowVersion,expectedHourlyFen:400,expectedPrepayFen:800,startAt:iso(start),endAt:iso(end)}
  assert.equal((await request('/reservations',owner.accessToken,body,undefined,key())).code,'QUOTE_CHANGED')
  Object.assign(body,{windowVersion:window.version,expectedHourlyFen:600,expectedPrepayFen:1200})
  const requestKey=key();reservation=await ok('/reservations',owner.accessToken,body,undefined,requestKey)
  assert.equal((await ok('/reservations',owner.accessToken,body,undefined,requestKey)).reservationId,reservation.reservationId)
  assert.equal((await request('/windows/'+window.id,owner.accessToken,{version:window.version,enabled:false},'PATCH')).code,'ACTIVE_RESERVATION')
  assert.equal((await request('/vehicles/'+vehicle.id,owner.accessToken,{version:vehicle.version,enabled:false},'PATCH')).code,'ACTIVE_RESERVATION')
  assert.equal(reservation.prepayFen,1200)
 })
 await check('community disable rejects new bookings but allows existing fulfillment',async()=>{
  c1=await ok('/communities/'+c1.id,admin,{version:c1.version,enabled:false},'PATCH')
  assert.equal((await request('/reservations',owner.accessToken,{vehicleId:vehicle.id,spaceId:space.id,startAt:iso(end),endAt:iso(end+3600000)},undefined,key())).code,'COMMUNITY_DISABLED')
  await until(()=>request('/bills/'+reservation.reservationId,owner.accessToken),r=>r.code==='OK')
  await ok('/payments',owner.accessToken,{reservationId:reservation.reservationId,kind:'PREPAY'},undefined,key())
  await until(()=>ok('/reservations/'+reservation.reservationId,owner.accessToken),r=>r.status==='CONFIRMED')
  await ok('/demo/clock',admin,{now:iso(start)})
  await ok('/property/reservations/'+reservation.reservationId+'/entry',propertyA.accessToken,{},undefined,key())
  await ok('/demo/clock',admin,{now:iso(end+20*60000)})
  await ok('/property/reservations/'+reservation.reservationId+'/exit',propertyA.accessToken,{},undefined,key())
  await until(()=>ok('/bills/'+reservation.reservationId,owner.accessToken),b=>b.status==='COMPLETED'&&b.overtimeFen===300)
  await ok('/payments',owner.accessToken,{reservationId:reservation.reservationId,kind:'OVERTIME'},undefined,key())
  const bill=await until(()=>ok('/bills/'+reservation.reservationId,owner.accessToken),b=>b.paidOvertimeFen===300)
  assert.equal(bill.paidPrepayFen+bill.paidOvertimeFen,1500)
  c1=await ok('/communities/'+c1.id,admin,{version:c1.version,enabled:true},'PATCH')
 })
 await check('audit authorization and account disable',async()=>{
  assert.equal((await request('/admin/audit-logs/parking',owner.accessToken)).status,403)
  assert.ok((await ok('/admin/audit-logs/identity',admin)).total>0)
  assert.ok((await ok('/admin/audit-logs/parking',admin)).total>0)
  await ok('/admin/users/'+users.other.id+'/status',admin,{enabled:false},'PATCH')
  assert.equal((await request('/auth/me',other.accessToken)).status,401)
  assert.equal((await request('/auth/login',null,{username:'other_'+suffix,password})).code,'ACCOUNT_DISABLED')
 })
}finally{await mkdir(dir,{recursive:true});await writeFile(dir+'/api-results.json',JSON.stringify({createdAt:new Date().toISOString(),results},null,2))}
