<script setup lang="ts">
import {onMounted,ref} from 'vue'
import {api,ApiError} from './api'
const props=defineProps<{userId:string}>()
const emit=defineEmits<{saved:[];close:[];expired:[]}>()
const choices:Record<string,string>={REFUND_READ:'查看退款',REFUND_APPROVE:'审批退款',FUNDS_RECOVER:'恢复失败退款',SETTLEMENT_EXECUTE:'执行结算'}
const selected=ref<string[]>([]),version=ref(0),ready=ref(false),busy=ref(false),message=ref('')
async function load(){ready.value=false;const result=await api<{version:number;permissions:string[]}>(`/admin/users/${props.userId}/permissions`);version.value=result.version;selected.value=result.permissions.filter(p=>p in choices);ready.value=true}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;message.value='';try{await action()}catch(e){message.value=e instanceof Error?e.message:'操作失败';if(e instanceof ApiError&&e.status===401)emit('expired')}finally{busy.value=false}}
async function save(){await run(async()=>{ready.value=false;await api(`/admin/users/${props.userId}/permissions`,{version:version.value,permissions:selected.value},undefined,'PUT');message.value='权限已保存，该账号须重新登录。';emit('saved');await load()})}
onMounted(()=>run(load))
</script>
<template>
 <section class="panel"><h2>账号资金权限</h2><p>目标账号：{{userId}}。保存后撤销该账号已有会话；其他管理权限不受此表单影响。</p>
 <p role="status">{{message}}</p>
 <form @submit.prevent="save"><label v-for="(label,key) in choices" :key="key"><input type="checkbox" :value="key" v-model="selected" :disabled="busy||!ready">{{label}}</label>
 <button :disabled="busy||!ready">保存资金权限</button><button type="button" :disabled="busy" @click="run(load)">重新读取权限</button><button type="button" :disabled="busy" @click="emit('close')">关闭</button></form>
 <p v-if="!ready">请重新读取以核对当前权限，确认后再保存。</p></section>
</template>
