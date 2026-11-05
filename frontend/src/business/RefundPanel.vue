<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, ApiError } from './api'
const emit=defineEmits<{expired:[]}>()
const props=defineProps<{userId:string;canRetry:boolean}>()
interface Attempt {id:string;reservationId:string;amountFen:number;status:string;retryOfId:string|null}
const items=ref<Attempt[]>([]), page=ref(1),total=ref(0),busy=ref(false),message=ref('')
const reason=ref(''), selected=ref<Attempt|null>(null)
const pending=ref<{id:string;reason:string;key:string}|null>(null)
const storageKey=`refund-retry:${props.userId}`
function remember(value:typeof pending.value){
 if(value)sessionStorage.setItem(storageKey,JSON.stringify(value));else sessionStorage.removeItem(storageKey)
 pending.value=value
}
function restore(){
 const raw=sessionStorage.getItem(storageKey)
 if(!raw)return
 const value=JSON.parse(raw)
 if(!value||typeof value.id!=='string'||!/^\d+$/.test(value.id)||typeof value.reason!=='string'||!value.reason.trim()||value.reason.length>500||typeof value.key!=='string'||!value.key)throw new Error('待核对退款记录格式异常，请联系管理员核查。')
 pending.value=value;reason.value=value.reason
}
const labels:Record<string,string>={FAILED:'失败',UNKNOWN:'结果未知',REVIEW:'待人工核查',SUCCESS:'成功',CREATED:'待执行',SUBMITTED:'处理中'}
async function load(){const data=await api<{items:Attempt[];total:number}>(`/admin/refund-attempts?page=${page.value}&size=20`);items.value=data.items;total.value=data.total}
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await work()}catch(e){message.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function retry(){if(!props.canRetry)return;await run(async()=>{
 if(!pending.value){if(!selected.value||!reason.value.trim())return;remember({id:selected.value.id,reason:reason.value.trim(),key:crypto.randomUUID()})}
 const request=pending.value
 if(!request)return
 try{const result=await api<Attempt>(`/admin/refund-attempts/${request.id}/retry`,{reason:request.reason},request.key);remember(null);selected.value=null;reason.value='';await load();message.value=`已受理，后继记录 ${result.id}；最终结果以刷新后的状态为准。`}
 catch(e){if(e instanceof ApiError&&e.status>=400&&e.status<500&&![401,429].includes(e.status)&&e.code!=='STALE_SESSION')remember(null);throw e}
})}
async function move(delta:number){await run(async()=>{page.value+=delta;await load()})}
onMounted(()=>run(async()=>{restore();await load()}))
</script>
<template>
 <h1>退款执行记录</h1><p>仅确定失败的自动退款可以重试；未知结果需先核查原通道记录。</p>
 <p v-if="message" role="status">{{message}}</p>
 <button :disabled="busy" @click="run(load)">刷新状态</button>
 <section class="table-wrap"><table><thead><tr><th>退款编号</th><th>预约编号</th><th>金额</th><th>状态</th><th>操作</th></tr></thead><tbody>
 <tr v-for="item in items" :key="item.id"><td>{{item.id}}</td><td>{{item.reservationId}}</td><td>{{(item.amountFen/100).toFixed(2)}}元</td><td>{{labels[item.status]||item.status}}</td><td><button v-if="canRetry && item.status==='FAILED'" :disabled="busy||!!pending" @click="selected=item;reason=''">重试</button></td></tr>
 </tbody></table><p v-if="!items.length">暂无退款记录</p></section>
 <p>第{{page}}页，共{{total}}条 <button :disabled="busy||page<=1" @click="move(-1)">上一页</button><button :disabled="busy||page*20>=total" @click="move(1)">下一页</button></p>
 <form v-if="canRetry && (selected||pending)" class="panel" @submit.prevent="retry"><p>为失败退款创建唯一后继；重复操作返回同一后继。</p><label>处理原因<textarea v-model="reason" required maxlength="500" :disabled="!!pending" /></label><button :disabled="busy||(!pending&&!reason.trim())">{{pending?'按原请求核对重试':'确认重试'}}</button><button v-if="!pending" type="button" :disabled="busy" @click="selected=null">取消</button></form>
</template>
