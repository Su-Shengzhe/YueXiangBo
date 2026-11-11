import {test,expect} from '@playwright/test'

test('AI management pages and persistent assistant session',async({page})=>{
 async function login(name:string){await page.getByLabel('账号',{exact:true}).fill(name);await page.getByLabel('密码',{exact:true}).fill(process.env['DEMO_'+name.toUpperCase()+'_PASSWORD']!);await page.getByRole('button',{name:'登录',exact:true}).click();await expect(page.getByText('登录成功',{exact:true})).toBeVisible()}
 await page.goto('/');await login('admin')
 await page.goto('/#/admin/ai-gateway');await expect(page.getByRole('heading',{name:'模型供应商管理'})).toBeVisible();await expect(page.getByText('上限：¥50',{exact:false})).toBeVisible()
 await page.goto('/#/admin/knowledge');await expect(page.getByRole('heading',{name:/知识|规则/}).first()).toBeVisible()
 await page.getByRole('button',{name:'退出登录'}).click();await login('driver')
 await page.goto('/#/assistant');await expect(page.getByRole('heading',{name:'AI停车助手'})).toBeVisible()
 await page.getByRole('button',{name:'开始新任务'}).click()
 await expect.poll(()=>page.evaluate(()=>sessionStorage.getItem('parking-ai-session:1'))).not.toBeNull()
 const id=await page.evaluate(()=>sessionStorage.getItem('parking-ai-session:1'))
 await page.reload();await login('driver');await page.getByRole('button',{name:'停车助手',exact:true}).click();await expect(page.getByRole('heading',{name:'AI停车助手'})).toBeVisible()
 expect(await page.evaluate(()=>sessionStorage.getItem('parking-ai-session:1'))).toBe(id)
 await expect(page.getByRole('button',{name:'发送',exact:true})).toBeEnabled()
})
