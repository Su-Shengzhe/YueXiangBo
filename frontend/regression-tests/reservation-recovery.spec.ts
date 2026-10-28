import { test, expect, type Page } from '@playwright/test'
async function login(page:Page,account='1'){
 await page.getByLabel('账号',{exact:true}).fill(account);await page.getByLabel('密码',{exact:true}).fill('test-password');await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('button',{name:'查询车位',exact:true})).toBeEnabled()
}
async function fixture(page:Page){
 const keys:string[]=[];let recoverable=false
 const order={reservationId:'100',plate:'测试车辆',startAt:'2030-01-01T01:00:00Z',endAt:'2030-01-01T02:00:00Z',status:'WAIT_PAY',prepayFen:400,payDeadline:'2030-01-01T00:10:00Z'}
 await page.route('**/api/v1/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname.replace('/api/v1','');let data:unknown={}
  if(path==='/auth/login'){const id=req.postDataJSON().username;data={accessToken:'test',user:{id,username:id,roles:['DRIVER']}}}
  else if(path==='/communities')data=[{id:'10',name:'测试社区'}]
  else if(path==='/vehicles')data=[{id:'40',plate:'测试车辆'}]
  else if(path==='/demo/clock')data={now:'2030-01-01T00:00:00Z'}
  else if(path==='/spaces/search')data={items:[{id:'20',name:'测试车位',hourlyFen:400,estimatedFen:400,windowId:'30',windowVersion:1}],total:1}
  else if(path==='/reservations'&&req.method()==='POST'){keys.push(req.headers()['idempotency-key']);if(keys.length===1){await route.abort();return}recoverable=true;data=order}
  else if(path==='/reservations')data=recoverable?[order]:[]
  else if(path.startsWith('/reservations/by-request-key/')){if(!recoverable){await route.fulfill({status:404,json:{code:'NOT_FOUND',message:'记录不存在'}});return}data=order}
  else if(path!=='/auth/logout')throw new Error('Unexpected path '+path)
  await route.fulfill({json:{code:'OK',data}})
 })
 await page.goto('/');await login(page)
 await page.getByRole('button',{name:'查询车位',exact:true}).click();await page.getByRole('button',{name:'预约此车位'}).click();await expect(page.getByLabel('待核对操作')).toBeVisible()
 return {keys,setRecoverable:()=>{recoverable=true}}
}
test('unknown reservation survives reload and same-account login; original key retries once',async({page})=>{
 const f=await fixture(page);await page.reload();await login(page);await expect(page.getByLabel('待核对操作')).toBeVisible();await page.getByRole('button',{name:'核对原操作'}).click();await expect(page.getByRole('status')).toContainText('不能确定原请求失败');expect(f.keys).toHaveLength(1)
 await page.getByRole('button',{name:'使用原请求重试'}).click();await expect(page.getByLabel('待核对操作')).toHaveCount(0);expect(f.keys).toHaveLength(2);expect(f.keys[0]).toBe(f.keys[1]);await expect(page.getByRole('row').filter({hasText:'测试车辆'})).toContainText('100')
})
test('finding committed result clears recovery without another creation',async({page})=>{
 const f=await fixture(page);f.setRecoverable();await page.getByRole('button',{name:'核对原操作'}).click();await expect(page.getByLabel('待核对操作')).toHaveCount(0);expect(f.keys).toHaveLength(1);await expect(page.getByRole('row').filter({hasText:'测试车辆'})).toContainText('100')
})
test('another account never restores the previous account operation',async({page})=>{
 const f=await fixture(page);await page.getByRole('button',{name:'退出登录'}).click();await login(page,'2');await expect(page.getByLabel('待核对操作')).toHaveCount(0);expect(f.keys).toHaveLength(1);expect(await page.evaluate(()=>localStorage.getItem('parking.pending-operation.v1'))).toBeNull()
})
test('storage unavailable warns but retains recovery within the page',async({page})=>{
 await page.addInitScript(()=>{Storage.prototype.setItem=function(){throw new Error('storage disabled')}});const f=await fixture(page);await expect(page.getByText('浏览器无法保存恢复记录',{exact:false})).toBeVisible();f.setRecoverable();await page.getByRole('button',{name:'核对原操作'}).click();await expect(page.getByLabel('待核对操作')).toHaveCount(0)
})
test('successful recovery does not hide a subsequent order refresh failure',async({page})=>{
 const f=await fixture(page);f.setRecoverable()
 await page.route('**/api/v1/reservations',async route=>{if(route.request().method()==='GET')await route.fulfill({status:503,json:{code:'DEPENDENCY_UNAVAILABLE',message:'订单刷新失败，请稍后重试'}});else await route.fallback()})
 await page.getByRole('button',{name:'核对原操作'}).click()
 await expect(page.getByRole('status')).toContainText('订单刷新失败')
 await expect(page.getByRole('status')).not.toContainText('列表已刷新')
 expect(f.keys).toHaveLength(1)
})
test('completing one tab preserves another tab request across reload',async({page,context})=>{
 await page.goto('/');const second=await context.newPage();await second.goto('/')
 const a={account:'1',kind:'reserve',key:'request-tab-a',path:'/reservations',body:{spaceId:'20'}}
 const b={...a,key:'request-tab-b',body:{spaceId:'21'}}
 for(const [tab,operation] of [[page,a],[second,b]] as const)await tab.evaluate(async value=>{const path='/src/business/pending-parking.ts';const m=await import(path);m.savePending(value)},operation)
 await page.evaluate(async value=>{const path='/src/business/pending-parking.ts';const m=await import(path);m.savePending(null,value)},a)
 await second.reload()
 const recovered=await second.evaluate(async()=>{const path='/src/business/pending-parking.ts';return (await import(path)).readPending('1')})
 expect(recovered.key).toBe(b.key)
 expect(await second.evaluate(async()=>{const path='/src/business/pending-parking.ts';return (await import(path)).readPending('2')})).toBeNull()
 await second.close()
})
test('malformed legacy recovery does not hide a valid current operation',async({page})=>{
 await page.goto('/');const found=await page.evaluate(async()=>{
  const path='/src/business/pending-parking.ts',m=await import(path)
  m.savePending({account:'1',kind:'reserve',key:'valid-request',path:'/reservations',body:{spaceId:'20'}})
  localStorage.setItem('parking.pending-operation.v1','{broken')
  return m.readPending('1')
 });expect(found.key).toBe('valid-request')
})
