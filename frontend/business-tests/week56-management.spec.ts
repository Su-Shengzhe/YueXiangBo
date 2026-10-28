import { test, expect, type Page, type APIRequestContext } from '@playwright/test'
const base='http://127.0.0.1:9090/api/v1', suffix=Date.now().toString(36), password=crypto.randomUUID()+'Aa9'
const names={owner:'wo_'+suffix,driver:'wd_'+suffix,property:'wp_'+suffix,foreign:'wf_'+suffix}
const users:Record<string,{id:string}>={}
let admin='',communityId='',spaceId='',start='',end='',reservationId=''
async function api(request:APIRequestContext,path:string,token?:string,body?:unknown,method?:string){const r=await request.fetch(base+path,{method:method||(body===undefined?'GET':'POST'),headers:{...(token?{Authorization:'Bearer '+token}:{}),'Idempotency-Key':crypto.randomUUID()},data:body});expect(r.ok(),path+': '+await r.text()).toBeTruthy();return (await r.json()).data}
async function login(page:Page,name:string,pass=password){await page.goto('/');await page.getByLabel('账号',{exact:true}).fill(name);await page.getByLabel('密码',{exact:true}).fill(pass);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByRole('button',{name:'退出登录',exact:true})).toBeVisible()}
async function logout(page:Page){await page.getByRole('button',{name:'退出登录',exact:true}).click();await expect(page.getByRole('heading',{name:'欢迎登录'})).toBeVisible()}
async function userRow(page:Page,name:string){await page.getByLabel('搜索用户',{exact:true}).fill(name);await page.getByRole('button',{name:'搜索用户',exact:true}).click();const row=page.getByRole('row').filter({hasText:name});await expect(row).toBeVisible();return row}

test.describe.serial('week 5-6 actual role management and new resource',()=>{
 test.beforeAll(async({request})=>{admin=(await api(request,'/auth/login',undefined,{username:'admin',password:process.env.DEMO_ADMIN_PASSWORD})).accessToken;for(const [role,name] of Object.entries(names))users[role]=await api(request,'/auth/register',undefined,{username:name,password})})
 test('platform creates community and grants roles and property using searchable UI',async({page,request})=>{
  await login(page,'admin',process.env.DEMO_ADMIN_PASSWORD!)
  await page.getByRole('button',{name:'社区管理',exact:true}).click();await page.getByRole('button',{name:'新增社区',exact:true}).click()
  await page.getByLabel('社区名称',{exact:true}).fill('新资源验收_'+suffix);await page.getByLabel('社区地址',{exact:true}).fill('虚拟社区B区')
  const saved=page.waitForResponse(r=>r.url().endsWith('/api/v1/communities')&&r.request().method()==='POST')
  await page.getByRole('button',{name:'保存',exact:true}).click();communityId=(await (await saved).json()).data.id;await expect(page.locator('.management [role=status]')).toContainText('已保存')
  await page.getByRole('button',{name:'用户与角色',exact:true}).click()
  await expect(page.getByRole('heading',{name:'用户与角色授权',exact:true})).toBeVisible()
  for(const role of ['owner','property','foreign'] as const){
   const row=await userRow(page,names[role]);await row.getByRole('button',{name:'设置角色'}).click()
   await page.getByLabel(role==='owner'?'OWNER':'PROPERTY',{exact:true}).check()
   await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.locator('.management [role=status]')).toContainText('已保存')
  }
  // Stale UI draft: a second administrator write advances the record version.
  const row=await userRow(page,names.owner);await row.getByRole('button',{name:'设置角色'}).click()
  const current=(await api(request,'/admin/users?query='+names.owner,admin)).items[0]
  await api(request,'/admin/users/'+current.id+'/roles',admin,{version:current.version,roles:current.roles},'PUT')
  await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.locator('.management [role=status]')).toContainText('草稿已保留')
  await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.locator('.management [role=status]')).toContainText('已保存')
  await page.getByRole('button',{name:'社区管理',exact:true}).click()
  // The created community can be beyond the first page of existing demo data.
  const communityRow=page.getByRole('row').filter({hasText:'新资源验收_'+suffix})
  await expect(page.locator('.management tbody tr').first()).toBeVisible()
  await expect(page.getByRole('button',{name:'刷新管理列表'})).toBeEnabled()
  for(let remaining=100;await communityRow.count()===0&&remaining>0;remaining--){await expect(page.getByRole('button',{name:'下一页',exact:true})).toBeEnabled();const loaded=page.waitForResponse(r=>r.url().includes('/api/v1/admin/communities?'));await page.getByRole('button',{name:'下一页',exact:true}).click();await loaded;await expect(page.getByRole('button',{name:'刷新管理列表'})).toBeEnabled()}
  await expect(communityRow).toBeVisible()
  await communityRow.getByRole('button',{name:'物业授权'}).click()
  await page.getByLabel('搜索物业用户',{exact:true}).fill(names.property);await page.getByRole('button',{name:'搜索物业',exact:true}).click()
  await page.getByLabel(names.property+'（'+users.property!.id+'）',{exact:true}).check()
  page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'保存物业授权'}).click();await expect(page.locator('.management [role=status]')).toContainText('社区授权已更新')
  await page.screenshot({path:process.env.BUSINESS_REPORT_DIR+'/week56-platform.png',fullPage:true})
  await logout(page)
 })
 test('owner publication, actual property rejection and approval, window in yuan and foreign denial',async({page,request})=>{
  await login(page,names.owner);await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'发布车位'}).click()
  await page.getByRole('combobox',{name:'所属社区',exact:true}).selectOption(communityId);await page.getByLabel('车位编号',{exact:true}).fill('UI-'+suffix)
  await page.getByLabel('位置说明',{exact:true}).fill('地下B区');await page.getByLabel('证明说明',{exact:true}).fill('虚拟证明')
  const saved=page.waitForResponse(r=>r.url().endsWith('/api/v1/spaces')&&r.request().method()==='POST')
  await page.getByRole('button',{name:'保存',exact:true}).click();spaceId=(await (await saved).json()).data.id
  await page.getByRole('button',{name:'提交审核'}).click();await expect(page.locator('.management tbody')).toContainText('待审核');await logout(page)
  await login(page,names.foreign);await page.getByRole('button',{name:'车位审核',exact:true}).click();await expect(page.getByLabel('审核社区').locator('option')).toHaveCount(0);await logout(page)
  const foreign=(await api(request,'/auth/login',undefined,{username:names.foreign,password})).accessToken
  const denied=await request.get(base+'/property/spaces?communityId='+communityId,{headers:{Authorization:'Bearer '+foreign}});expect(denied.status()).toBe(403)
  await login(page,names.property);await page.getByRole('button',{name:'车位审核',exact:true}).click()
  const reviewRow=page.getByRole('row').filter({hasText:'UI-'+suffix});await expect(reviewRow).toBeVisible()
  page.once('dialog',d=>d.accept('请补充位置说明'));await reviewRow.getByRole('button',{name:'驳回',exact:true}).click();await expect(reviewRow).toContainText('已驳回');await logout(page)
  await login(page,names.owner);await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'编辑车位'}).click()
  await page.getByLabel('位置说明',{exact:true}).fill('地下B区入口右侧');await page.getByRole('button',{name:'保存',exact:true}).click();await page.getByRole('button',{name:'提交审核'}).click();await expect(page.locator('.management tbody')).toContainText('待审核');await logout(page)
  await login(page,names.property);await page.getByRole('button',{name:'车位审核',exact:true}).click();page.once('dialog',d=>d.accept('审核通过'));await reviewRow.getByRole('button',{name:'通过',exact:true}).click();await expect(reviewRow).toContainText('审核通过');await logout(page)
  await login(page,names.owner);await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'共享窗口',exact:true}).click()
  const now=(await api(request,'/demo/clock',admin)).now,t=Math.ceil((Date.parse(now)+3600000)/900000)*900000
  start=new Date(t).toISOString();end=new Date(t+7200000).toISOString()
  const input=(n:number)=>new Date(n+8*3600000).toISOString().slice(0,16)
  await page.getByLabel('窗口开始',{exact:true}).fill(input(t));await page.getByLabel('窗口结束',{exact:true}).fill(input(t+10800000))
  await page.getByLabel('小时价格（元／小时）',{exact:true}).fill('4.00');await page.getByRole('button',{name:'保存窗口'}).click();await expect(page.locator('.window-calendar')).toContainText('¥4.00')
  await page.screenshot({path:process.env.BUSINESS_REPORT_DIR+'/week56-windows.png',fullPage:true});await logout(page)
 })
 test('new driver and approved resource complete 8 + 2 = 10 yuan through actual role UI',async({page,request})=>{
  await login(page,names.driver);await page.getByRole('button',{name:'车辆管理',exact:true}).click();await page.getByRole('button',{name:'绑定车辆'}).click()
  await page.getByLabel('车牌号码',{exact:true}).fill('沪A'+suffix.slice(-5).toUpperCase());await page.getByRole('button',{name:'保存',exact:true}).click();await expect(page.locator('.management tbody tr')).toHaveCount(1)
  await page.getByRole('button',{name:'查询与预约'}).click();await page.getByRole('combobox',{name:'社区',exact:true}).selectOption(communityId)
  const input=(s:string)=>new Date(Date.parse(s)+8*3600000).toISOString().slice(0,16)
  await page.getByLabel('开始时间',{exact:true}).fill(input(start));await page.getByLabel('结束时间',{exact:true}).fill(input(end));await page.getByRole('button',{name:'查询车位',exact:true}).click();await expect(page.locator('.space-grid')).toContainText('¥8.00')
  const created=page.waitForResponse(r=>r.url().endsWith('/api/v1/reservations')&&r.request().method()==='POST');await page.getByRole('button',{name:'预约此车位'}).click();reservationId=(await (await created).json()).data.reservationId
  await page.getByRole('row').filter({hasText:reservationId}).getByRole('button',{name:'查看账单'}).click();await expect(page.getByRole('button',{name:'模拟支付预约费'})).toBeVisible({timeout:20000});await page.getByRole('button',{name:'模拟支付预约费'}).click();await expect(page.locator('.bill .status')).toHaveText('预约已确认',{timeout:20000});await logout(page)
  await login(page,names.owner);await page.getByRole('button',{name:'业主车位管理'}).click();await page.getByRole('button',{name:'共享窗口',exact:true}).click();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'停用窗口',exact:true}).click();await expect(page.locator('.management [role=status]')).toContainText('有效预约');await logout(page)
  await api(request,'/demo/clock',admin,{now:start});await login(page,names.property);await page.getByRole('button',{name:'物业入离场',exact:true}).click()
  const orderRow=page.getByRole('row').filter({hasText:reservationId});await expect(orderRow).toBeVisible();await orderRow.getByRole('button',{name:'办理入场'}).click();await expect(orderRow).toContainText('停车中')
  await api(request,'/demo/clock',admin,{now:new Date(Date.parse(end)+1200000).toISOString()});await orderRow.getByRole('button',{name:'办理离场'}).click();await expect(orderRow).toContainText('已离场');await logout(page)
  await login(page,names.driver);await page.getByRole('button',{name:'我的预约与账单'}).click();await page.getByRole('row').filter({hasText:reservationId}).getByRole('button',{name:'查看账单'}).click();await expect(page.getByRole('button',{name:'模拟支付超时费'})).toBeVisible({timeout:20000});await page.getByRole('button',{name:'模拟支付超时费'}).click();await expect(page.locator('.amounts > div').filter({hasText:'已付合计'}).locator('b')).toHaveText('¥10.00',{timeout:20000})
  await page.screenshot({path:process.env.BUSINESS_REPORT_DIR+'/week56-ten-yuan-bill.png',fullPage:true});await logout(page)
  await login(page,'admin',process.env.DEMO_ADMIN_PASSWORD!);await page.getByRole('button',{name:'操作审计',exact:true}).click();await expect(page.locator('.management tbody')).toContainText('修改角色');await page.getByLabel('审计来源').selectOption('parking');await expect(page.locator('.management tbody')).toContainText('新增窗口')
  await logout(page)
 })
})
