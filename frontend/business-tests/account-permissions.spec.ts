import {test,expect} from '@playwright/test'

test('account finance permissions revoke sessions and enforce cross-service access',async({page,request})=>{
 const base='http://127.0.0.1:9090/api/v1'
 const username='finance_'+Date.now(),password='Test!'+crypto.randomUUID()
 const registered=await request.post(base+'/auth/register',{data:{username,password}})
 expect(registered.ok()).toBeTruthy()
 const id=(await registered.json()).data.id
 const admin=await request.post(base+'/auth/login',{data:{username:'admin',password:process.env.DEMO_ADMIN_PASSWORD}})
 const headers={Authorization:'Bearer '+(await admin.json()).data.accessToken}
 const role=await request.put(`${base}/admin/users/${id}/roles`,{headers,data:{version:1,roles:['PLATFORM']}})
 expect(role.ok()).toBeTruthy()
 async function login(){const response=await request.post(base+'/auth/login',{data:{username,password}});expect(response.ok()).toBeTruthy();return (await response.json()).data.accessToken as string}
 const before=await login()
 try{
  const revoked=await request.put(`${base}/admin/users/${id}/permissions`,{headers,data:{version:2,permissions:[]}})
  expect(revoked.ok()).toBeTruthy()
  expect((await request.get(base+'/admin/refund-attempts',{headers:{Authorization:'Bearer '+before}})).status()).toBe(401)
  const denied=await login()
  expect((await request.put(`${base}/admin/users/${id}/permissions`,{headers:{Authorization:'Bearer '+denied},data:{version:3,permissions:['REFUND_APPROVE']}})).status()).toBe(403)
  expect((await request.put(`${base}/admin/users/${id}/roles`,{headers:{Authorization:'Bearer '+denied},data:{version:3,roles:['PLATFORM']}})).status()).toBe(403)
  expect((await request.get(base+'/admin/refund-attempts',{headers:{Authorization:'Bearer '+denied}})).status()).toBe(403)
  await page.goto('/')
  await page.getByLabel('账号',{exact:true}).fill('admin')
  await page.getByLabel('密码',{exact:true}).fill(process.env.DEMO_ADMIN_PASSWORD!)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('button',{name:'用户与角色',exact:true}).click()
  await page.getByLabel('搜索用户').fill(username)
  await page.getByRole('button',{name:'搜索用户',exact:true}).click()
  await page.getByRole('button',{name:'资金权限',exact:true}).click()
  await page.getByLabel('查看退款',{exact:true}).check()
  await page.getByRole('button',{name:'保存资金权限',exact:true}).click()
  await expect(page.getByText('权限已保存，该账号须重新登录。',{exact:true})).toBeVisible()
  const readOnly=await login()
  expect((await request.get(base+'/admin/refund-attempts',{headers:{Authorization:'Bearer '+readOnly}})).status()).toBe(200)
  expect((await request.post(base+'/admin/refund-attempts/1/retry',{headers:{Authorization:'Bearer '+readOnly,'Idempotency-Key':crypto.randomUUID()},data:{reason:'验证无恢复权限'}})).status()).toBe(403)
 }finally{
  const current=await request.get(`${base}/admin/users/${id}/permissions`,{headers})
  if(current.ok()){
   const version=(await current.json()).data.version
   const cleanup=await request.patch(`${base}/admin/users/${id}/status`,{headers,data:{version,enabled:false}})
   expect(cleanup.ok()).toBeTruthy()
  }
 }
})
