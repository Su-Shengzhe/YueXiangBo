<script setup lang="ts">
import {ref,onMounted,computed,watch} from 'vue'
import {api,ApiError} from './api'
const emit=defineEmits<{expired:[]}>()
type Provider={id:string;providerName:string;modelName:string;baseUrl:string;credentialRef:string;enabled:boolean}
type PaidBudget={supplierHost:string;limitMicroyuan:number;approved:boolean}
type State={paidBudgets:PaidBudget[];route:{version:number;providerRevisionId:string|null;previousRevisionId:string|null};providers:Provider[];budget:{spentMicroyuan:number;reservedMicroyuan:number;limitMicroyuan:number;warningMicroyuan:number;version:number;paused:boolean;freeAccessConfirmed:boolean};history:{version:number;providerRevisionId:string}[]}
const state=ref<State|null>(null),busy=ref(false),message=ref(''),ready=ref(false)
const form=ref({providerName:'Agnes',protocol:'OPENAI_CHAT',baseUrl:'https://apihub.agnes-ai.com/v1',modelName:'agnes-2.5-flash',credentialRef:'agnes.properties',inputPriceMicroyuanPerMillion:400000,outputPriceMicroyuanPerMillion:1200000,priceVersion:'agnes-list-usd-fx8-v1',freeAccessApproved:true,maxOutputTokens:1536})
const logs=ref<Record<string,unknown>[]>([]),free=ref(false),reconciliation=ref({requestId:'',equivalentMicroyuan:0,actualChargeMicroyuan:0,evidence:''})
const billingMode=ref('FREE'),paidApproved=ref(false),paidYuan=ref<number|null>(null),disableThinking=ref(false)
const existingBudget=computed(()=>{
 try{return state.value?.paidBudgets.find(b=>b.supplierHost===new URL(form.value.baseUrl).hostname.toLowerCase().replace(/[.]$/,''))}
 catch{return undefined}
})
watch(()=>form.value.baseUrl,()=>{form.value.freeAccessApproved=false;paidApproved.value=false;paidYuan.value=null})
watch(existingBudget,b=>{if(b)billingMode.value='PAID'})
async function budget(paused:boolean){await run(async()=>{await api('/admin/ai-gateway/budget',{version:state.value!.budget.version,paused,freeAccessConfirmed:free.value});await load()})}
async function loadLogs(){await run(async()=>{const r=await api<{items:Record<string,unknown>[]}>('/admin/ai-call-logs?size=50');logs.value=r.items})}
async function reconcile(){await run(async()=>{const {requestId,...body}=reconciliation.value;await api('/admin/ai-gateway/calls/'+encodeURIComponent(requestId)+'/reconcile',body);await load();message.value='用量已核对。若存在实际扣费，调用已自动暂停。'})}
async function load(){ready.value=false;state.value=await api<State>('/admin/ai-gateway');ready.value=true}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function create(){await run(async()=>{await api('/admin/ai-gateway/providers',{...form.value,billingMode:billingMode.value,freeAccessApproved:billingMode.value==='FREE'&&form.value.freeAccessApproved,...(disableThinking.value?{disableThinking:true}:{}),...(billingMode.value==='PAID'?{paidApproved:paidApproved.value,paidLimitMicroyuan:existingBudget.value?.limitMicroyuan??Math.round((paidYuan.value??0)*1000000)}:{})});await load();message.value='已保存新的供应商配置版本。'})}
async function checkProvider(id:string){await run(async()=>{await api(`/admin/ai-gateway/providers/${id}/check`,{});await load();message.value="配置检查通过，10分钟内可发布。"})}
async function publish(providerRevisionId:string){await run(async()=>{ready.value=false;try{await api('/admin/ai-gateway/publish',{providerRevisionId,version:state.value!.route.version});await load();message.value='活动路由已更新。'}catch(e){await load().catch(()=>{});throw e}})}
async function rollback(){await run(async()=>{ready.value=false;try{await api('/admin/ai-gateway/rollback',{version:state.value!.route.version});await load();message.value='已发布上一配置。'}catch(e){await load().catch(()=>{});throw e}})}
onMounted(()=>run(load))
</script>
<template>
 <section><h1>模型供应商管理</h1><p>为停车助手选择模型。已有配置保留为历史版本，修改时保存新版本。</p>
 <p v-if="message" role="status">{{message}}</p><button :disabled="busy" @click="run(load)">刷新并核对</button>
 <template v-if="state"><p>当前路由版本：{{state.route.version}}；活动配置：{{state.route.providerRevisionId||'未设置'}}</p>
 <p>等值用量：¥{{(state.budget.spentMicroyuan/1000000).toFixed(4)}}；预留：¥{{(state.budget.reservedMicroyuan/1000000).toFixed(4)}}；上限：¥{{state.budget.limitMicroyuan/1000000}}</p>
 <p v-if="state.budget.spentMicroyuan>=state.budget.warningMicroyuan" role="alert">已达到等值用量提醒线。</p>
 <p>调用状态：{{state.budget.paused?'暂停':'开放'}}；免费资格：{{state.budget.freeAccessConfirmed?'已确认':'待确认'}}。等值金额不是实际扣费。</p>
 <label><input v-model="free" type="checkbox">我已核实当前账号免费资格，允许继续免费调用</label><button :disabled="busy" @click="budget(true)">暂停调用</button><button :disabled="busy||!free" @click="budget(false)">确认并恢复免费调用</button>
 <button :disabled="busy||!ready||!state.route.previousRevisionId" @click="rollback">恢复上一配置</button>
 <table><thead><tr><th>版本</th><th>供应商</th><th>模型</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="p in state.providers" :key="p.id"><td>{{p.id}}</td><td>{{p.providerName}}</td><td>{{p.modelName}}</td><td>{{p.enabled?'可用':'停用'}}</td><td><button :disabled="busy||!p.enabled" @click="checkProvider(p.id)">检查配置</button><button :disabled="busy||!ready||!p.enabled||p.id===state.route.providerRevisionId" @click="publish(p.id)">设为活动配置</button></td></tr></tbody></table>
 </template>
 <form @submit.prevent="create"><h2>保存供应商配置</h2><label>名称<input v-model="form.providerName" required maxlength="80"></label><label>接口地址<input v-model="form.baseUrl" required type="url"></label><label>模型名称<input v-model="form.modelName" required maxlength="128"></label><label>本地凭据文件名<input v-model="form.credentialRef" required pattern="[a-zA-Z0-9_-]{1,60}\.properties"></label><label>输入等值价格（微元/百万Token）<input v-model.number="form.inputPriceMicroyuanPerMillion" type="number" min="1" required></label><label>输出等值价格（微元/百万Token）<input v-model.number="form.outputPriceMicroyuanPerMillion" type="number" min="1" required></label><label>单次最大输出 Token<input v-model.number="form.maxOutputTokens" type="number" min="1" max="32768" step="1" required></label><label>价格版本<input v-model="form.priceVersion" required></label><label>计费方式<select v-model="billingMode" :disabled="!!existingBudget"><option value="FREE">免费</option><option value="PAID">付费</option></select></label>
 <template v-if="billingMode==='PAID'"><p v-if="existingBudget">此供应商共享已授权额度 ¥{{existingBudget.limitMicroyuan/1000000}}</p><template v-else><label>供应商累计额度（元）<input v-model.number="paidYuan" type="number" min="0.000001" step="0.000001" :max="state ? state.budget.limitMicroyuan/1000000 : undefined" required></label><label><input v-model="paidApproved" type="checkbox" required>授权此供应商在上述额度内调用</label></template></template>
 <label><input v-model="disableThinking" type="checkbox">发送关闭思考模式参数（仅支持该参数的模型）</label>
 <label v-if="billingMode==='FREE'"><input v-model="form.freeAccessApproved" type="checkbox">已核实该配置的免费调用资格</label><p>凭据保存在服务端私密目录，页面不读取或展示API Key。100万微元等于1元。Agnes默认预算折算采用8元/美元的保守系数，并非实时汇率。</p><button :disabled="busy">保存新版本</button></form>
 <h2>调用与额度核对</h2><button :disabled="busy" @click="loadLogs">查看最近50条调用</button><table><thead><tr><th>调用编号</th><th>状态</th><th>等值微元</th><th>实际扣费微元</th></tr></thead><tbody><tr v-for="log in logs" :key="String(log.requestId)"><td>{{log.requestId}}</td><td>{{log.status}}</td><td>{{log.chargedMicroyuan ?? '结果未知，额度预留中'}}</td><td>{{log.actualChargeMicroyuan ?? '尚未核对账单'}}</td></tr></tbody></table>
 <form @submit.prevent="reconcile"><h3>核对未知调用</h3><label>调用编号<input v-model="reconciliation.requestId" required></label><label>核对等值金额（微元）<input v-model.number="reconciliation.equivalentMicroyuan" type="number" min="0" required></label><label>实际扣费（微元）<input v-model.number="reconciliation.actualChargeMicroyuan" type="number" min="0" required></label><label>供应商账单或工单依据<input v-model="reconciliation.evidence" required maxlength="100"></label><button :disabled="busy">依据账单核对</button></form>
 </section>
</template>
<style scoped>table{width:100%;border-collapse:collapse;margin:16px 0}th,td{text-align:left;padding:10px;border-bottom:1px solid #ddd}form{display:grid;gap:12px;max-width:640px}label{display:grid;gap:6px}input{padding:8px}button{padding:8px;cursor:pointer}button:disabled{cursor:default;opacity:.5}</style>
