<script setup lang="ts">
import { ref } from 'vue'
import { ElAlert, ElButton, ElCard, ElForm, ElFormItem, ElInput, ElTable, ElTableColumn } from 'element-plus'
import 'element-plus/theme-chalk/el-alert.css'
import 'element-plus/theme-chalk/el-button.css'
import 'element-plus/theme-chalk/el-card.css'
import 'element-plus/theme-chalk/el-form.css'
import 'element-plus/theme-chalk/el-form-item.css'
import 'element-plus/theme-chalk/el-input.css'
import 'element-plus/theme-chalk/el-table.css'
import 'element-plus/theme-chalk/el-table-column.css'
const token=ref('')
const label=ref('悦享泊环境验证')
const rows=ref<Array<{id:string,label:string,created_at:string}>>([])
const busy=ref(false)
const status=ref('请输入本地验证令牌，再读取或保存测试记录。')
const failed=ref(false)
async function run(create: boolean) {
  busy.value=true
  failed.value=false
  const headers={'Content-Type':'application/json','X-Probe-Token':token.value}
  try {
    if(create) {
      const response=await fetch('/api/dev/probes',{method:'POST',headers,body:JSON.stringify({label:label.value}),signal:AbortSignal.timeout(10000)})
      if(!response.ok) throw new Error(`保存失败：HTTP ${response.status}`)
    }
    const response=await fetch('/api/dev/probes',{headers,signal:AbortSignal.timeout(10000)})
    if(!response.ok) throw new Error(`读取失败：HTTP ${response.status}`)
    rows.value=await response.json()
    status.value=create?'记录已保存，并从数据库重新读取。':'已从数据库读取记录。'
  } catch(error) {
    failed.value=true
    status.value=error instanceof Error?error.message:'请求失败'
  } finally { busy.value=false }
}
</script>
<template>
  <main>
    <p class="eyebrow">悦享泊——智慧社区车位共享与结算平台</p>
    <h1>开发环境验证</h1>
    <p>此页验证网关与数据库连接，尚未实现预约、支付或结算业务。</p>
    <el-card>
      <el-form label-position="top">
        <el-form-item label="本地验证令牌"><el-input v-model="token" type="password" show-password autocomplete="off" /></el-form-item>
        <el-form-item label="测试记录名称"><el-input v-model="label" maxlength="80" /></el-form-item>
        <el-button type="primary" :loading="busy" :disabled="!token || !label.trim()" @click="run(true)">保存并验证</el-button>
        <el-button :disabled="busy || !token" @click="run(false)">读取记录</el-button>
      </el-form>
      <el-alert :title="status" :type="failed?'error':'info'" :closable="false" />
      <el-table :data="rows" empty-text="暂无测试记录">
        <el-table-column prop="label" label="记录名称" />
        <el-table-column prop="id" label="记录编号" />
      </el-table>
    </el-card>
  </main>
</template>
<style scoped>
main{max-width:920px;margin:48px auto;padding:0 20px;font-family:system-ui,sans-serif;color:#203047}
.eyebrow{color:#456c69;font-size:14px}h1{font-size:32px}p{line-height:1.7}
.el-alert{margin-top:24px}.el-card{margin-top:24px}
</style>
