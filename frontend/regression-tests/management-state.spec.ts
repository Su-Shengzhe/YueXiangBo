import { test, expect, type Page } from '@playwright/test'
import { yuanToFen } from '../src/business/money'

test('yuan parser preserves cents and rejects overprecision and out of range input',()=>{
 expect(yuanToFen('0.29')).toBe(29);expect(yuanToFen('4.00')).toBe(400);expect(yuanToFen('10000')).toBe(1000000)
 for(const value of ['1.001','-1','NaN','1e3','10000.01','0'])expect(()=>yuanToFen(value)).toThrow()
 expect(yuanToFen('0',true)).toBe(0)
})
async function fixture(page:Page){
 await page.route('**/api/v1/**',async route=>{
  const path=new URL(route.request().url()).pathname.replace('/api/v1','')
  const data=path==='/auth/login'?{accessToken:'mock',user:{id:'1',username:'manager',roles:['DRIVER','OWNER','PROPERTY','PLATFORM'],communities:['10','11']}}:
   (path==='/property/communities'||path==='/communities')?[{id:'10',name:'社区A'},{id:'11',name:'社区B'}]:path==='/vehicles'?[{id:'40',plate:'测试车辆'}]:path==='/demo/clock'?{now:'2030-01-01T00:00:00Z'}:{items:[],total:0,page:1,size:20}
  await route.fulfill({json:{code:'OK',data}})
 })
 await page.goto('/');await page.getByLabel('账号',{exact:true}).fill('manager');await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('button',{name:'退出登录'})).toBeVisible()
}

test('failed window detail switch clears previous windows and blocks writes until retry succeeds',async({page})=>{
 await fixture(page);let failB=true;const writes:string[]=[]
 await page.route('**/api/v1/owner/spaces?**',r=>r.fulfill({json:{data:{items:[{id:'A',name:'车位A'},{id:'B',name:'车位B'}],total:2}}}))
 await page.route('**/api/v1/spaces/*/windows?**',async r=>{const id=new URL(r.request().url()).pathname.split('/')[4];if(id==='B'&&failB){await r.fulfill({status:503,json:{code:'UNAVAILABLE',message:'详情加载失败'}});return}await r.fulfill({json:{data:{items:[{id:'window-'+id,spaceId:id,version:1,enabled:true,hourlyFen:id==='A'?400:600,startAt:'2030-01-01T01:00:00Z',endAt:'2030-01-01T03:00:00Z'}],total:1}}})})
 await page.route('**/api/v1/windows/*',async r=>{writes.push(new URL(r.request().url()).pathname);await r.fulfill({json:{data:{}}})})
 await page.getByRole('button',{name:'业主车位管理'}).click()
 const open=(name:string)=>page.getByRole('row').filter({hasText:name}).getByRole('button',{name:'共享窗口'})
 await open('车位A').click();await expect(page.locator('.window-calendar')).toContainText('4.00')
 await page.getByRole('button',{name:'编辑窗口'}).click();await open('车位B').click()
 await expect(page.locator('.management [role=status]')).toContainText('详情加载失败');await expect(page.locator('.window-calendar article')).toHaveCount(0);await expect(page.getByRole('button',{name:'保存窗口',exact:true})).toBeDisabled();expect(writes).toEqual([])
 failB=false;await page.getByRole('button',{name:'刷新管理列表'}).click();await expect(page.locator('.window-calendar')).toContainText('6.00');await expect(page.getByRole('button',{name:'保存窗口',exact:true})).toBeEnabled()
 page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'停用窗口'}).click();await expect.poll(()=>writes).toEqual(['/api/v1/windows/window-B'])
})

test('failed authorization switch never submits previous community users or version',async({page})=>{
 await fixture(page);let failB=true;const writes:unknown[]=[]
 await page.route('**/api/v1/admin/communities?**',r=>r.fulfill({json:{data:{items:[{id:'A',name:'社区A'},{id:'B',name:'社区B'}],total:2}}}))
 await page.route('**/api/v1/admin/communities/*/administrators',async r=>{const id=new URL(r.request().url()).pathname.split('/')[5];if(r.request().method()==='PUT'){writes.push({id,...r.request().postDataJSON()});await r.fulfill({json:{data:{}}});return}if(id==='B'&&failB){await r.fulfill({status:503,json:{code:'UNAVAILABLE',message:'详情加载失败'}});return}await r.fulfill({json:{data:{communityId:id,version:3,userIds:[id+'-user'],users:[{id:id+'-user',username:id+'物业',enabled:true}]}}})})
 await page.getByRole('button',{name:'社区管理',exact:true}).click()
 const open=(name:string)=>page.getByRole('row').filter({hasText:name}).getByRole('button',{name:'物业授权'})
 await open('社区A').click();await expect(page.locator('.manage-form')).toContainText('A物业');await open('社区B').click()
 await expect(page.locator('.management [role=status]')).toContainText('详情加载失败');await expect(page.locator('.manage-form')).not.toContainText('A物业');await expect(page.getByRole('button',{name:'保存物业授权'})).toBeDisabled();expect(writes).toEqual([])
 failB=false;await page.getByRole('button',{name:'刷新管理列表'}).click();await expect(page.locator('.manage-form')).toContainText('B物业');page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'保存物业授权'}).click();await expect.poll(()=>writes).toEqual([{id:'B',version:3,userIds:['B-user']}])
})
test('changing review community during slow request never renders old community data',async({page})=>{
 await fixture(page)
 await page.route('**/api/v1/property/spaces?**',async route=>{const id=new URL(route.request().url()).searchParams.get('communityId');if(id==='10')await new Promise(r=>setTimeout(r,600));await route.fulfill({json:{data:{items:[{id,version:1,name:id==='10'?'旧社区私有车位':'当前社区车位',reviewStatus:'PENDING',enabled:true}],total:1,page:1,size:20}}})})
 await page.getByRole('button',{name:'车位审核',exact:true}).click();await page.getByLabel('审核社区').selectOption('11')
 await expect(page.locator('.management tbody')).toContainText('当前社区车位');await expect(page.getByRole('button',{name:'刷新管理列表'})).toBeEnabled();await expect(page.locator('.management tbody')).not.toContainText('旧社区私有车位')
})
test('lost write response reloads state and blocks a blind duplicate creation',async({page})=>{
 await fixture(page);let writes=0
 await page.route('**/api/v1/vehicles',async route=>{if(route.request().method()==='POST'){writes++;await route.abort('connectionreset')}else await route.fallback()})
 await page.getByRole('button',{name:'车辆管理',exact:true}).click();await page.getByRole('button',{name:'绑定车辆'}).click();await page.getByLabel('车牌号码',{exact:true}).fill('粤B12345');await page.getByRole('button',{name:'保存',exact:true}).click()
 await expect(page.locator('.management [role=alert]')).toContainText('结果待确认');await expect(page.getByRole('button',{name:'保存',exact:true})).toBeDisabled();expect(writes).toBe(1)
 await page.getByRole('button',{name:'刷新管理列表'}).click();await expect(page.getByRole('button',{name:'已核对记录，恢复操作'})).toBeEnabled();expect(writes).toBe(1)
})
test('stale role version preserves draft and requires explicit second save',async({page})=>{
 await fixture(page);let version=1,writes=0
 await page.route('**/api/v1/admin/users?**',route=>route.fulfill({json:{data:{items:[{id:'2',username:'owner',roles:['DRIVER'],enabled:true,version}],total:1,page:1,size:20}}}))
 await page.route('**/api/v1/admin/users/2/roles',async route=>{writes++;if(writes===1){version=2;await route.fulfill({status:409,json:{code:'STALE_VERSION',message:'记录已变化'}})}else{expect(route.request().postDataJSON()).toEqual({roles:['DRIVER','OWNER'],version:2});await route.fulfill({json:{data:{}}})}})
 await page.getByRole('button',{name:'用户与角色',exact:true}).click();await page.getByRole('button',{name:'设置角色'}).click();await page.getByLabel('OWNER',{exact:true}).check();await page.getByRole('button',{name:'保存',exact:true}).click()
 await expect(page.locator('.management [role=status]')).toContainText('草稿已保留');await expect(page.getByLabel('OWNER',{exact:true})).toBeChecked();expect(writes).toBe(1)
 await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.locator('.management [role=status]')).toContainText('已保存');expect(writes).toBe(2)
})

test('mobile management remains within viewport and rapid navigation keeps the requested route',async({page})=>{
 await page.setViewportSize({width:390,height:844});await fixture(page)
 await page.getByRole('button',{name:'车辆管理',exact:true}).click()
 await expect(page.getByRole('heading',{name:'我的车辆',exact:true})).toBeVisible()
 await page.getByRole('button',{name:'绑定车辆'}).click()
 await expect(page.getByLabel('车牌号码',{exact:true})).toBeVisible()
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true)
 await page.getByRole('button',{name:'社区管理',exact:true}).click()
 await page.getByRole('button',{name:'用户与角色',exact:true}).click()
 await expect(page.getByRole('heading',{name:'用户与角色授权',exact:true})).toBeVisible()
 await expect(page).toHaveURL(/#\/admin\/users$/)
})

for(const assigned of [true,false])test(`mixed roles keep public communities independent of property scope (${assigned})`,async({page})=>{
 await fixture(page)
 await page.route('**/api/v1/property/communities',route=>route.fulfill({json:{data:assigned?[{id:'11',name:'社区B'}]:[]}}))
 await page.getByRole('button',{name:'退出登录'}).click();await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click()
 await expect(page.getByRole('combobox',{name:'社区',exact:true}).locator('option')).toHaveCount(2)
 await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'发布车位'}).click()
 await expect(page.getByRole('combobox',{name:'所属社区',exact:true}).locator('option')).toHaveCount(2)
 await page.getByRole('button',{name:'物业入离场'}).click()
 await expect(page.getByRole('combobox',{name:'履约社区',exact:true}).locator('option')).toHaveCount(assigned?1:0)
})

test('property orders ignore delayed old community and page responses',async({page})=>{
 await fixture(page)
 let release!:()=>void;const delayed=new Promise<void>(r=>release=r)
 let began!:()=>void;const requested=new Promise<void>(r=>began=r)
 const order=(id:string)=>({reservationId:id,plate:'测试',status:'CONFIRMED',prepayFen:400,startAt:'2030-01-01T01:00:00Z',endAt:'2030-01-01T02:00:00Z'})
 await page.route('**/api/v1/property/reservations?**',async route=>{const id=new URL(route.request().url()).searchParams.get('communityId');if(id==='10'){began();await delayed}await route.fulfill({json:{data:[order(id==='10'?'old-community':'current-community')]}})})
 await page.route('**/api/v1/reservations',route=>route.fulfill({json:{data:[order('own-order')]}}))
 await page.getByRole('button',{name:'物业入离场'}).click();await requested
 await page.getByRole('combobox',{name:'履约社区'}).selectOption('11')
 await expect(page.locator('tbody')).toContainText('current-community')
 await page.getByRole('button',{name:'我的预约与账单'}).click();await expect(page.locator('tbody')).toContainText('own-order')
 const response=page.waitForResponse(r=>r.url().includes('communityId=10'));release();await response
 await expect(page.locator('tbody')).toContainText('own-order');await expect(page.locator('tbody')).not.toContainText('old-community')
})

test('unknown management write survives navigation and same-account relogin without exposing it to another account',async({page})=>{
 await fixture(page);let writes=0
 await page.route('**/api/v1/vehicles',async route=>{if(route.request().method()==='POST'){writes++;await route.abort('connectionreset')}else await route.fallback()})
 await page.getByRole('button',{name:'车辆管理'}).click();await page.getByRole('button',{name:'绑定车辆'}).click();await page.getByLabel('车牌号码',{exact:true}).fill('粤B12345');await page.getByRole('button',{name:'保存',exact:true}).click()
 await expect(page.getByRole('alert')).toContainText('结果待确认')
 await page.getByRole('button',{name:'查询与预约'}).click();await page.getByRole('button',{name:'车辆管理'}).click();await expect(page.getByRole('alert')).toContainText('结果待确认')
 await page.getByRole('button',{name:'退出登录'}).click();await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'车辆管理'}).click()
 await expect(page.getByRole('alert')).toContainText('结果待确认');expect(writes).toBe(1)
 await page.reload();await page.getByLabel('账号',{exact:true}).fill('manager');await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'车辆管理'}).click();await expect(page.getByRole('alert')).toContainText('结果待确认')
 await page.getByRole('button',{name:'退出登录'}).click()
 await page.route('**/api/v1/auth/login',route=>route.fulfill({json:{data:{accessToken:'another-mock',user:{id:'2',username:'other',roles:['DRIVER']}}}}))
 await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'车辆管理'}).click();await expect(page.getByRole('alert')).toHaveCount(0)
 await page.getByRole('button',{name:'退出登录'}).click();await page.unroute('**/api/v1/auth/login');await page.getByLabel('密码',{exact:true}).fill('mock-password');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('button',{name:'车辆管理'}).click();await expect(page.getByRole('alert')).toContainText('结果待确认')
 await page.getByRole('button',{name:'刷新管理列表'}).click();await page.getByRole('button',{name:'已核对记录，恢复操作'}).click();await expect(page.getByRole('alert')).toHaveCount(0)
})

test('mobile driver and owner query, orders, publication and windows fit viewport',async({page})=>{
 await page.setViewportSize({width:390,height:844});await fixture(page)
 const order={reservationId:'mobile-order',plate:'测试车辆',status:'COMPLETED',prepayFen:800,startAt:'2030-01-01T01:00:00Z',endAt:'2030-01-01T03:00:00Z'}
 await page.route('**/api/v1/reservations',route=>route.fulfill({json:{data:[order]}}))
 await page.route('**/api/v1/bills/mobile-order',route=>route.fulfill({json:{data:{...order,overtimeFen:200,paidPrepayFen:800,paidOvertimeFen:200,refundFen:0,attempts:[]}}}))
 await page.route('**/api/v1/spaces/search?**',route=>route.fulfill({json:{data:{items:[{id:'20',name:'手机车位',hourlyFen:400,estimatedFen:800,windowId:'30',windowVersion:1}],total:1}}}))
 await page.route('**/api/v1/owner/spaces?**',route=>route.fulfill({json:{data:{items:[{id:'20',spaceCode:'手机车位',version:1,enabled:true,reviewStatus:'APPROVED'}],total:1,page:1,size:20}}}))
 const fits=async()=>expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true)
 await page.getByRole('button',{name:'查询车位',exact:true}).click();await expect(page.locator('.space-grid')).toContainText('手机车位');await fits()
 await page.getByRole('button',{name:'我的预约与账单'}).click();await expect(page.locator('tbody')).toContainText('mobile-order');await fits();await page.getByRole('button',{name:'查看账单'}).click();await expect(page.locator('.bill')).toContainText('¥10.00');await fits()
 await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'发布车位'}).click();await fits();await page.getByRole('button',{name:'关闭表单'}).click()
 await page.getByRole('button',{name:'共享窗口',exact:true}).click();await expect(page.getByLabel('小时价格（元／小时）')).toBeVisible();await fits()
})
