<script setup lang="ts">
import {ref,onMounted} from 'vue'
import {api,ApiError} from './api'
const emit=defineEmits<{expired:[]}>()
type Entry={id:string;ruleId:string;ruleVersion:number;communityId:string;title:string;content:string;keywords:string;status:string;version:number}
const items=ref<Entry[]>([]),busy=ref(false),message=ref(''),page=ref(1),total=ref(0)
const form=ref({ruleId:'',ruleVersion:1,communityId:'0',title:'',topic:'GENERAL',keywords:'',content:'',effectiveAt:new Date().toISOString(),expiresAt:''})
async function load(){const r=await api<{items:Entry[];total:number}>(`/admin/knowledge?page=${page.value}&size=20`);items.value=r.items;total.value=r.total}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function create(){await run(async()=>{await api('/admin/knowledge',form.value);await load();message.value='草稿已保存，发布后才用于回答。'})}
async function action(row:Entry,type:string){await run(async()=>{await api(`/admin/knowledge/${row.id}/${type}`,{version:row.version});await load()})}
async function save(row:Entry){await run(async()=>{await api(`/admin/knowledge/${row.id}`,{version:row.version,title:row.title,content:row.content,keywords:row.keywords},undefined,'PATCH');await load()})}
onMounted(()=>run(load))
</script>
<template><section><h1>停车规则知识库</h1><p>发布后正文保持不变；修订时建立新版本。停用后不参与新的规则检索。</p><p v-if="message" role="status">{{message}}</p><button :disabled="busy" @click="run(load)">刷新</button>
<article v-for="r in items" :key="r.id" class="panel"><h3>{{r.ruleId}} · v{{r.ruleVersion}} · {{r.status}}</h3><label>标题<input v-model="r.title" :disabled="r.status!=='DRAFT'"></label><label>关键词<input v-model="r.keywords" :disabled="r.status!=='DRAFT'"></label><label>内容<textarea v-model="r.content" :disabled="r.status!=='DRAFT'" rows="3" /></label><button v-if="r.status==='DRAFT'" :disabled="busy" @click="save(r)">保存草稿</button><button v-if="r.status==='DRAFT'" :disabled="busy" @click="action(r,'publish')">发布</button><button v-if="r.status==='PUBLISHED'" :disabled="busy" @click="action(r,'disable')">停用</button></article>
<button :disabled="busy||page<=1" @click="page--;run(load)">上一页</button><span> {{page}}页 / {{total}}条 </span><button :disabled="busy||page*20>=total" @click="page++;run(load)">下一页</button>
<form class="panel" @submit.prevent="create"><h2>新建规则版本</h2><label>规则编号<input v-model="form.ruleId" pattern="[A-Z0-9_]+" required></label><label>版本<input v-model.number="form.ruleVersion" type="number" min="1" required></label><label>社区编号（0为全局）<input v-model="form.communityId" required></label><label>标题<input v-model="form.title" required maxlength="128"></label><label>关键词（空格分隔）<input v-model="form.keywords" required></label><label>内容<textarea v-model="form.content" maxlength="2000" required /></label><label>生效时间（带时区）<input v-model="form.effectiveAt" required></label><label>失效时间（可空）<input v-model="form.expiresAt"></label><button :disabled="busy">保存草稿</button></form></section></template>
<style scoped>.panel{margin:14px 0;max-width:850px}label{margin:10px 0}button{margin:8px 8px 0 0}</style>
