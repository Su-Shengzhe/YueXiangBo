<script setup lang="ts">
import {ref,onMounted,onUnmounted} from 'vue'
import {api,ApiError} from './api'
const props=defineProps<{userId:string;reservationId?:string}>(),emit=defineEmits<{expired:[]}>()
type Answer={requestId:string;sessionVersion:number;status:string;answer?:string;cards?:Record<string,unknown>[];citations?:{ruleId:string;version:number;title:string;clause:string}[];bill?:Record<string,unknown>}
type History={sessionVersion:number;activeRequest:string|null;items:{requestId:string;requestKey:string;status:string;resultJson:string|null}[]}
const text=ref('提前离场能退钱吗？'),session=ref(''),version=ref(0),busy=ref(false),error=ref(''),answers=ref<Answer[]>([]),active=ref<string|null>(null)
let timer:ReturnType<typeof setInterval>|undefined,disposed=false,polling=false
const storage='parking-ai-session:'+props.userId
const pending=ref<{key:string;body:unknown}|null>(null)
async function create(){const r=await api<{sessionId:string;sessionVersion:number}>('/ai/sessions',{});session.value=r.sessionId;version.value=r.sessionVersion;try{sessionStorage.setItem(storage,r.sessionId)}catch{}}
function fault(e:unknown){error.value=e instanceof Error?e.message:'助手暂不可用';if(e instanceof ApiError&&e.status===401)emit('expired')}
async function history(){if(!session.value||polling||disposed)return;polling=true;try{const r=await api<History>(`/ai/sessions/${session.value}/messages`);if(disposed)return;version.value=r.sessionVersion;active.value=r.activeRequest;answers.value=r.items.filter(x=>x.resultJson).map(x=>JSON.parse(x.resultJson!) as Answer).reverse();busy.value=!!active.value;if(!active.value&&pending.value&&r.items.some(x=>x.requestKey===pending.value!.key&&x.resultJson)){pending.value=null;try{sessionStorage.removeItem(storage+':pending')}catch{}}}catch(e){fault(e)}finally{polling=false}}
async function send(){if(busy.value||!text.value.trim())return;error.value='';busy.value=true;try{if(!session.value)await create();if(!pending.value){pending.value={key:crypto.randomUUID(),body:{text:text.value,expectedSessionVersion:version.value,context:props.reservationId?{reservationId:props.reservationId}:{}}};try{sessionStorage.setItem(storage+':pending',JSON.stringify(pending.value))}catch{}}
 const result=await api<Answer>(`/ai/sessions/${session.value}/messages`,pending.value.body,pending.value.key);if(disposed)return;version.value=result.sessionVersion;if(result.status==='PROCESSING'){active.value=result.requestId}else{answers.value.push(result);pending.value=null;try{sessionStorage.removeItem(storage+':pending')}catch{};busy.value=false}
 }catch(e){fault(e);busy.value=false;/* Retain the original key until history proves the outcome. */}}
async function fresh(){if(busy.value)return;try{if(session.value)await api(`/ai/sessions/${session.value}`,undefined,undefined,'DELETE');session.value='';answers.value=[];pending.value=null;try{sessionStorage.removeItem(storage);sessionStorage.removeItem(storage+':pending')}catch{};await create();error.value=''}catch(e){fault(e)}}
onMounted(async()=>{try{session.value=sessionStorage.getItem(storage)||'';const p=sessionStorage.getItem(storage+':pending');if(p)pending.value=JSON.parse(p)}catch{};if(session.value)await history();timer=setInterval(()=>{if(active.value||pending.value)void history()},1200)})
onUnmounted(()=>{disposed=true;if(timer)clearInterval(timer)})
</script>
<template><section><h1>AI停车助手</h1><p>可查找车位、查看停车规则、解释本人账单。预约和资金操作请在对应业务页面确认。</p><p v-if="reservationId">当前预约：{{reservationId}}</p>
<div><button :disabled="busy" @click="fresh">开始新任务</button> <button @click="history">刷新并核对结果</button></div>
<form class="panel" @submit.prevent="send"><label>你的问题<textarea v-model="text" maxlength="500" required rows="3" :disabled="busy" /></label><button class="primary" :disabled="busy">{{busy?'正在处理…':pending?'按原请求重试':'发送'}}</button></form>
<p v-if="error" role="alert" class="error">{{error}}</p>
<article v-for="a in answers" :key="a.requestId" class="panel reply"><small>{{a.status==='FALLBACK'?'已使用备用说明':a.status==='CLARIFY'?'需要补充条件':a.status==='REFUSED'?'无法执行该请求':a.status==='FAILED'?'处理已中断':'助手回答'}}</small><p class="answer-text">{{a.answer}}</p>
<div v-for="c in a.cards" :key="String(c.id)" class="card"><b>{{c.name}}</b><p>{{c.locationDesc}} · {{c.spaceType}}</p><p>每小时 ¥{{(Number(c.hourlyFen)/100).toFixed(2)}}；预估 ¥{{(Number(c.estimatedFen)/100).toFixed(2)}}</p><a href="#/spaces">前往查询与预约</a></div>
<details v-for="c in a.citations" :key="c.ruleId+':'+c.version"><summary>{{c.title}} · {{c.ruleId}} v{{c.version}}</summary><p>{{c.clause}}</p></details>
<p v-if="a.bill">账单版本 {{a.bill.billVersion}}，查询时间 {{a.bill.billAsOf}}。<a href="#/orders">查看本人订单</a></p>
</article></section></template>
<style scoped>.panel{margin-top:16px}.reply{max-width:900px}.answer-text{white-space:pre-wrap}.card{border:1px solid #cbd9d3;border-radius:8px;margin:10px 0;padding:12px}details{margin:10px 0}a{color:#17644a}button{margin-top:8px}</style>
