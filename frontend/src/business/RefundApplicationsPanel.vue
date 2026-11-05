<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {api,ApiError} from './api'
import {yuanToFen} from './money'
const props=defineProps<{userId:string;admin:boolean;canApprove:boolean}>()
const emit=defineEmits<{expired:[]}>()
interface Application{id:string;reservationId:string;amountFen:number;reason:string;status:'PENDING'|'APPROVED'|'REJECTED'|'COMPLETED'|'PARTIAL_FAILED';decisionNote?:string;evidenceText?:string;previousApplicationId?:string}
const rows=ref<Application[]>([]),busy=ref(false),message=ref(''),reservation=ref(''),amount=ref(''),reason=ref(''),note=ref(''),page=ref(1),total=ref(0)
const evidence=ref(''),previous=ref('')
function resubmit(row:Application){reservation.value=row.reservationId;amount.value=(row.amountFen/100).toFixed(2);reason.value=row.reason;evidence.value='';previous.value=row.id}
const labels:Record<string,string>={PENDING:'待审批',APPROVED:'退款执行中',REJECTED:'已驳回',COMPLETED:'退款完成',PARTIAL_FAILED:'执行含失败项，待核查'}
async function load(){const data=await api<{items:Application[];total:number}>(`/refund-applications?page=${page.value}&size=20`);rows.value=data.items;total.value=data.total}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function apply(){await run(async()=>{const body={reservationId:reservation.value,amountFen:yuanToFen(amount.value),reason:reason.value.trim(),evidence:evidence.value.trim(),previousApplicationId:previous.value||null};const storage=`refund-application:${props.userId}`;const old=sessionStorage.getItem(storage);const operation=old?JSON.parse(old):{body,key:crypto.randomUUID()};if(old&&JSON.stringify(operation.body)!==JSON.stringify(body))throw new Error('已有未确认申请，请恢复原预约、金额和原因后核对，或刷新列表确认结果。');sessionStorage.setItem(storage,JSON.stringify(operation));try{await api('/refund-applications',operation.body,operation.key)}catch(e){if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,429].includes(e.status)&&e.code!=='STALE_SESSION')sessionStorage.removeItem(storage);throw e}sessionStorage.removeItem(storage);await load();message.value='申请已受理，等待审批。'})}
const decisionStorage=()=>`refund-decision:${props.userId}`
const pendingDecision=ref(false)
async function submitDecision(){
 const raw=sessionStorage.getItem(decisionStorage());if(!raw)return
 const operation=JSON.parse(raw)
 try{await api(`/refund-applications/${operation.id}/decision`,operation.body,operation.key)}catch(e){
  if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,429].includes(e.status)&&e.code!=='STALE_SESSION'){sessionStorage.removeItem(decisionStorage());pendingDecision.value=false}
  throw e
 }
 sessionStorage.removeItem(decisionStorage());pendingDecision.value=false;await load();note.value='';message.value='审批结果已确认，退款结果以执行状态为准。'
}
async function decide(id:string,decision:string){await run(async()=>{
 if(sessionStorage.getItem(decisionStorage()))throw new Error('请先核对未确认的审批结果。')
 sessionStorage.setItem(decisionStorage(),JSON.stringify({id,body:{decision,note:note.value},key:crypto.randomUUID()}));pendingDecision.value=true;await submitDecision()
})}
onMounted(()=>run(async()=>{pendingDecision.value=!!sessionStorage.getItem(decisionStorage());const raw=sessionStorage.getItem(`refund-application:${props.userId}`);if(raw){const saved=JSON.parse(raw);reservation.value=saved.body.reservationId;amount.value=(saved.body.amountFen/100).toFixed(2);reason.value=saved.body.reason;evidence.value=saved.body.evidence||'';previous.value=saved.body.previousApplicationId||''}await load()}))
</script>
<template>
 <section class="panel"><h2>{{admin?'退款申请审批':'退款申请'}}</h2><p role="status">{{message}}</p>
 <form v-if="!admin" @submit.prevent="apply"><p>完成后7天内可申请部分退款，驳回后须补充新证据。</p><label>预约编号<input v-model="reservation" required pattern="[0-9]+"></label><label>申请金额（元）<input v-model="amount" required inputmode="decimal"></label><label>退款原因<textarea v-model="reason" required maxlength="500" /></label><label>证据说明<textarea v-model="evidence" maxlength="2000" :required="!!previous" /></label><p v-if="previous">关联被驳回申请：{{previous}}</p><button :disabled="busy">提交退款申请</button></form>
 <button v-if="admin&&pendingDecision" :disabled="busy" @click="run(submitDecision)">核对未确认审批</button><label v-if="admin&&canApprove">审批说明<textarea v-model="note" maxlength="500" /></label>
 <button :disabled="busy" @click="run(load)">刷新退款申请</button>
 <table><thead><tr><th>申请编号</th><th>预约</th><th>金额</th><th>原因</th><th>状态</th><th>审批说明</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td>{{row.id}}</td><td>{{row.reservationId}}</td><td>{{(row.amountFen/100).toFixed(2)}}元</td><td>{{row.reason}}<p>{{row.evidenceText}}</p><small v-if="row.previousApplicationId">关联申请 {{row.previousApplicationId}}</small></td><td>{{labels[row.status]||row.status}}</td><td>{{row.decisionNote}}</td><td v-if="!admin&&row.status==='REJECTED'"><button :disabled="busy" @click="resubmit(row)">补充证据重提</button></td><td v-if="admin&&canApprove&&row.status==='PENDING'"><button :disabled="busy||pendingDecision||!note.trim()" @click="decide(row.id,'APPROVE')">批准退款</button><button :disabled="busy||pendingDecision||!note.trim()" @click="decide(row.id,'REJECT')">驳回退款</button></td></tr></tbody></table>
 <p>第{{page}}页，共{{total}}条 <button :disabled="busy||page<=1" @click="run(async()=>{page--;await load()})">上页</button><button :disabled="busy||page*20>=total" @click="run(async()=>{page++;await load()})">下页</button></p>
 </section>
</template>

