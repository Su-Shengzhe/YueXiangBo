<script setup lang="ts">
import { ref, reactive, computed, watch, onUnmounted } from 'vue'
import { api, ApiError } from './api'
import { businessDate, businessInstant, businessInput } from './business-time'
import { yuanToFen, fenToYuan } from './money'
import { pendingManagement } from './pending-management'
import type { User, Community, ManagementRow, ShareWindow, PageResult, ManagedUser, Authorization, Role } from './types'
type Row = ManagementRow
type Page = PageResult<Row>
const props=defineProps<{ section:string; user:User; communities:Community[]; now:string }>()
const emit=defineEmits<{ expired:[]; changed:[] }>()
const rows=ref<Row[]>([]), total=ref(0), page=ref(1), busy=ref(false), notice=ref(''), failed=ref(false)
const community=ref(props.user.communities?.[0] || props.communities[0]?.id || ''), source=ref('identity')
const selectedSpace=ref<Row|null>(null), editing=ref<Row|null>(null), formOpen=ref(false), form=reactive<Record<string,string|number|boolean|undefined>>({}), windowRows=ref<ShareWindow[]>([])
const windowPage=ref(1), windowTotal=ref(0), windowEditing=ref<ShareWindow|null>(null), windowForm=reactive({startAt:'',endAt:'',hourlyYuan:'5.00'})
const roles=ref<Role[]>([]), authCommunity=ref(''), authUsers=ref<string[]>([]), authVersion=ref(0)
const windowsReady=ref(false), authorizationReady=ref(false)
const userQuery=ref(''), candidateQuery=ref(''), candidatePage=ref(1), candidateTotal=ref(0), candidates=ref<ManagedUser[]>([])
const pending=pendingManagement(props.user.id,props.section)
const selectedNames=ref<Record<string,string>>({}), unknownWrite=ref(pending.read()), checkedUnknown=ref(false)
async function write(path:string,body:unknown,key?:string,method?:string){
 pending.set(true)
 try {const result=await api(path,body,key,method);pending.set(false);return result}
 catch(e){if(!(e instanceof ApiError)||!['WRITE_UNKNOWN','STALE_SESSION'].includes(e.code))pending.set(false);throw e}
}
function confirmUnknown(){pending.set(false);unknownWrite.value=false;checkedUnknown.value=false}
let alive=true, listRequest=0, candidateRequest=0, windowRequest=0
onUnmounted(()=>{alive=false;listRequest++;candidateRequest++;windowRequest++})
const titles:Record<string,string>={vehicles:'我的车辆',owner:'我的车位',reviews:'车位审核与管理',communities:'社区管理',users:'用户与角色授权',audit:'操作审计'}
const fields=computed(()=>props.section==='vehicles'?[['plate','车牌号码']]:props.section==='communities'?[['name','社区名称'],['address','社区地址']]:[['spaceCode','车位编号'],['locationDesc','位置说明'],['proofText','证明说明']])
const labels:Record<string,string>={DRAFT:'草稿',PENDING:'待审核',APPROVED:'审核通过',REJECTED:'已驳回'}
async function handleError(e:unknown){
 if(!alive||e instanceof ApiError&&e.code==='STALE_SESSION')return
 failed.value=true;notice.value=e instanceof Error?e.message:'请求失败'
 if(e instanceof ApiError&&e.status===401){emit('expired');return}
 if(e instanceof ApiError&&e.code==='WRITE_UNKNOWN'){unknownWrite.value=true;checkedUnknown.value=false;await load().catch(()=>{});return}
 if(e instanceof ApiError&&e.code==='STALE_VERSION'){
  await load().catch(()=>{})
  if(editing.value){const fresh=rows.value.find(r=>r.id===editing.value?.id);if(fresh)editing.value=fresh}
  if(selectedSpace.value){await loadWindows().catch(()=>{});if(windowEditing.value)windowEditing.value=windowRows.value.find(w=>w.id===windowEditing.value?.id)||windowEditing.value}
  if(authCommunity.value)await loadAuthorization(true).catch(()=>{})
  if(alive)notice.value='记录已变化，已读取最新版本。草稿已保留，请核对列表和草稿后再次确认保存。'
 }
}
async function run(work:()=>Promise<void>){if(busy.value)return;busy.value=true;notice.value='';failed.value=false;try{await work()}catch(e){await handleError(e)}finally{if(alive)busy.value=false}}
function endpoint(){return props.section==='vehicles'?'/manage/vehicles':props.section==='owner'?'/owner/spaces':props.section==='reviews'?'/property/spaces?communityId='+community.value:props.section==='communities'?'/admin/communities':props.section==='users'?'/admin/users':'/admin/audit-logs/'+source.value}
async function load(){const request=++listRequest;if(props.section==='reviews'&&!community.value){rows.value=[];total.value=0;return}const url=endpoint();const data=await api<Page>(url+(url.includes('?')?'&':'?')+new URLSearchParams({page:String(page.value),size:'20',...(props.section==='users'?{query:userQuery.value}:{})}));if(!alive||request!==listRequest)return;rows.value=data.items;total.value=data.total}
async function refresh(){await run(async()=>{await load();if(selectedSpace.value)await loadWindows();if(authCommunity.value&&!authorizationReady.value)await loadAuthorization();if(unknownWrite.value)checkedUnknown.value=true})}
async function searchUsers(){page.value=1;await run(load)}
function start(row:Row|null=null){editing.value=row;for(const k of Object.keys(form))delete form[k];Object.assign(form,row||{communityId:props.communities[0]?.id||'',spaceType:'STANDARD'});roles.value=[...(row?.roles||[])];formOpen.value=true;notice.value=''}
async function save(){if(unknownWrite.value)return;await run(async()=>{const section=props.section,id=editing.value?.id;let path=section==='vehicles'?'/vehicles':section==='communities'?'/communities':section==='users'?'/admin/users/'+id+'/roles':'/spaces';let body:Record<string,unknown>;
 if(section==='users')body={roles:roles.value,version:editing.value!.version};else if(section==='vehicles')body={plate:form.plate};else if(section==='communities')body={name:form.name,address:form.address};else body={spaceCode:form.spaceCode,locationDesc:form.locationDesc,proofText:form.proofText,spaceType:form.spaceType,...(!id?{communityId:form.communityId}:{})};
 if(id&&section!=='users'){path+='/'+id;body.version=editing.value!.version}
 await write(path,body,undefined,section==='users'?'PUT':id?'PATCH':'POST');formOpen.value=false;await load();emit('changed');notice.value='已保存';if(section==='users'&&id===props.user.id)emit('expired')})}
async function toggle(row:Row){if(unknownWrite.value)return;if(!confirm('确认'+(row.enabled?'停用':'启用')+'此记录？历史记录将保留。'))return;await run(async()=>{let path=props.section==='vehicles'?'/vehicles/':props.section==='communities'?'/communities/':'/admin/users/';path+=row.id+(props.section==='users'?'/status':'');await write(path,{enabled:!row.enabled,version:row.version},undefined,'PATCH');await load();emit('changed');notice.value='状态已更新';if(props.section==='users'&&row.id===props.user.id)emit('expired')})}
async function submit(row:Row){if(unknownWrite.value)return;await run(async()=>{await write('/spaces/'+row.id+'/submit',{version:row.version});await load();notice.value='已提交物业审核'})}
async function review(row:Row,decision:string){if(unknownWrite.value)return;const comment=prompt(decision==='APPROVE'?'请输入通过意见':'请输入驳回原因');if(!comment?.trim())return;await run(async()=>{await write('/spaces/'+row.id+'/reviews',{version:row.version,decision,comment});await load();notice.value='审核已记录'})}
async function ban(row:Row){if(unknownWrite.value)return;const reason=prompt(row.enabled?'请输入封禁原因':'请输入恢复原因');if(!reason?.trim())return;await run(async()=>{await write('/spaces/'+row.id+'/status',{version:row.version,enabled:!row.enabled,reason},undefined,'PATCH');await load();notice.value='车位状态已更新'})}
async function loadWindows(){const request=++windowRequest,id=selectedSpace.value!.id;windowsReady.value=false;windowRows.value=[];windowTotal.value=0;const data=await api<PageResult<ShareWindow>>('/spaces/'+id+'/windows?page='+windowPage.value+'&size=20');if(!alive||request!==windowRequest||id!==selectedSpace.value?.id)return;windowRows.value=data.items;windowTotal.value=data.total;windowsReady.value=true}
async function openWindows(row:Row){if(busy.value)return;selectedSpace.value=row;windowPage.value=1;windowEditing.value=null;const start=Math.ceil((new Date(props.now).getTime()+3600000)/900000)*900000;windowForm.startAt=businessInput(start);windowForm.endAt=businessInput(start+3600000);windowForm.hourlyYuan='5.00';await run(loadWindows)}
function currentWindow(row:ShareWindow){return windowsReady.value&&row.spaceId===selectedSpace.value?.id&&windowRows.value.some(w=>w.id===row.id)}
function editWindow(row:ShareWindow){if(busy.value||!currentWindow(row))return;windowEditing.value=row;windowForm.startAt=businessInput(new Date(row.startAt).getTime());windowForm.endAt=businessInput(new Date(row.endAt).getTime());windowForm.hourlyYuan=fenToYuan(row.hourlyFen)}
async function saveWindow(){if(unknownWrite.value||!windowsReady.value||!selectedSpace.value||windowEditing.value&&!currentWindow(windowEditing.value))return;await run(async()=>{const row=windowEditing.value;await write(row?'/windows/'+row.id:'/spaces/'+selectedSpace.value!.id+'/windows',{startAt:businessInstant(windowForm.startAt),endAt:businessInstant(windowForm.endAt),hourlyFen:yuanToFen(windowForm.hourlyYuan),...(row?{version:row.version}:{})},undefined,row?'PATCH':'POST');windowEditing.value=null;await loadWindows();notice.value='共享窗口已保存'})}
async function toggleWindow(row:ShareWindow){if(unknownWrite.value||!currentWindow(row))return;if(!confirm('确认'+(row.enabled?'停用':'启用')+'此窗口？有关有效预约时操作将被拒绝。'))return;await run(async()=>{await write('/windows/'+row.id,{version:row.version,enabled:!row.enabled},undefined,'PATCH');await loadWindows()})}
async function loadCandidates(){const request=++candidateRequest;const data=await api<PageResult<ManagedUser>>('/admin/users?'+new URLSearchParams({page:String(candidatePage.value),size:'20',query:candidateQuery.value,role:'PROPERTY',enabled:'true'}));if(!alive||request!==candidateRequest)return;candidates.value=data.items;candidateTotal.value=data.total;for(const u of data.items)selectedNames.value[u.id]=u.username}
async function loadAuthorization(preserveDraft=false){const id=authCommunity.value;authorizationReady.value=false;const result=await api<Authorization>('/admin/communities/'+id+'/administrators');if(!alive||authCommunity.value!==id)return;if(!preserveDraft){authUsers.value=result.userIds;selectedNames.value=Object.fromEntries(result.users.map(u=>[u.id,u.username]))}authVersion.value=result.version;authorizationReady.value=true}
async function openAuthorization(row:Row){if(busy.value)return;authCommunity.value=row.id;authorizationReady.value=false;authUsers.value=[];authVersion.value=0;selectedNames.value={};candidates.value=[];candidateTotal.value=0;candidateRequest++;candidatePage.value=1;candidateQuery.value='';await run(async()=>{await loadAuthorization();if(alive&&authCommunity.value===row.id)await loadCandidates()})}
async function saveAuthorization(){if(unknownWrite.value||!authorizationReady.value||!authCommunity.value||!confirm('保存将替换该社区的全部物业授权，并使受影响账号重新登录。'))return;await run(async()=>{await write('/admin/communities/'+authCommunity.value+'/administrators',{userIds:authUsers.value,version:authVersion.value},undefined,'PUT');authCommunity.value='';authorizationReady.value=false;notice.value='社区授权已更新'})}
const auditNames:Record<string,string>={REGISTER:'注册账号',USER_STATUS:'修改账号状态',USER_ROLES:'修改角色',COMMUNITY_AUTHORIZE:'物业授权',COMMUNITY_CREATE:'创建社区',COMMUNITY_EDIT:'修改社区',VEHICLE_CREATE:'绑定车辆',VEHICLE_EDIT:'修改车辆',SPACE_CREATE:'创建车位',SPACE_EDIT:'修改车位',SPACE_SUBMIT:'提交审核',SPACE_REVIEW:'车位审核',SPACE_STATUS:'封禁或恢复车位',WINDOW_CREATE:'新增窗口',WINDOW_EDIT:'修改窗口'}
function auditSummary(row:Row){try{const d=JSON.parse(row.detailJson||'{}') as Record<string,unknown>;return [d.decision==='APPROVE'?'审核通过':d.decision==='REJECT'?'审核驳回':'',typeof d.enabled==='boolean'?(d.enabled?'启用':'停用'):'',typeof d.comment==='string'?d.comment:'',typeof d.reason==='string'?d.reason:'',Array.isArray(d.roles)?'角色：'+d.roles.join(' / '):'',Array.isArray(d.userIds)?'授权人数：'+d.userIds.length:''].filter(Boolean).join(' · ')||'操作已完成'}catch{return '操作已完成'}}
watch(()=>props.section,()=>{page.value=1;rows.value=[];selectedSpace.value=null;formOpen.value=false;authCommunity.value='';void run(load)},{immediate:true})
watch([community,source],()=>{page.value=1;rows.value=[];void load().catch(handleError)})
</script>
<template>
 <section class="management"><div class="title-row"><h1>{{titles[section]}}</h1><button @click="refresh" :disabled="busy">刷新管理列表</button></div>
 <p v-if="notice" role="status" :class="['notice',{error:failed}]">{{notice}}</p>
 <p v-if="unknownWrite" role="alert" class="notice">上次保存结果待确认，已暂停写操作。请刷新并核对相关记录；窗口及授权操作请打开对应详情核对，已有记录无需重建。<button :disabled="!checkedUnknown||busy" @click="confirmUnknown">已核对记录，恢复操作</button></p>
 <form v-if="section==='users'" @submit.prevent="searchUsers"><label>搜索用户<input v-model="userQuery" maxlength="40" placeholder="用户名或用户 ID"></label><button :disabled="busy">搜索用户</button></form>
 <div class="manage-tools"><button v-if="['vehicles','owner','communities'].includes(section)" @click="start()" :disabled="busy">{{section==='vehicles'?'绑定车辆':section==='owner'?'发布车位':'新增社区'}}</button>
 <label v-if="section==='reviews'">审核社区<select v-model="community"><option v-for="c in communities.filter(c=>user.roles.includes('PLATFORM')||user.communities?.includes(c.id))" :value="c.id" :key="c.id">{{c.name}}</option></select></label>
 <label v-if="section==='audit'">审计来源<select v-model="source"><option value="identity">账号与授权</option><option value="parking">停车资源</option></select></label></div>
 <p v-if="busy" aria-live="polite">正在处理…</p>
 <div class="panel table-wrap"><table><thead><tr><th>记录</th><th>{{section==='audit'?'操作详情':'状态与详情'}}</th><th v-if="section!=='audit'">操作</th></tr></thead><tbody>
 <tr v-for="row in rows" :key="row.id"><td><b>{{row.plate||row.name||row.username||(row.action&&(auditNames[row.action]||row.action))}}</b><br><code>{{row.id}}</code><small v-if="row.createdAt">{{businessDate(row.createdAt || '')}}</small></td>
 <td><template v-if="section==='audit'">操作者 {{row.actorId}} · 对象 {{row.resourceId}} · 成功<p>{{auditSummary(row)}}</p><details><summary>原始详情</summary><p class="audit-detail">{{row.detailJson}}</p></details></template><template v-else><span class="status">{{row.enabled?'启用':'停用'}}</span> <span v-if="row.reviewStatus">{{labels[row.reviewStatus]}}</span><p>{{row.address||row.locationDesc||row.roles?.join(' / ')}}</p><small v-if="row.proofText">证明：{{row.proofText}}</small><small v-if="row.reviewComment">审核意见：{{row.reviewComment}}</small><small v-if="row.communities?.length">物业社区：{{row.communities.join(', ')}}</small></template></td>
 <td v-if="section!=='audit'" class="actions"><template v-if="['vehicles','communities','users'].includes(section)"><button @click="start(row)" :disabled="busy">{{section==='users'?'设置角色':'编辑'}}</button><button @click="toggle(row)" :disabled="busy">{{row.enabled?'停用':'启用'}}</button><button v-if="section==='communities'" @click="openAuthorization(row)" :disabled="busy">物业授权</button></template>
 <template v-if="section==='owner'"><button @click="start(row)" :disabled="busy||row.reviewStatus==='PENDING'">编辑车位</button><button v-if="['DRAFT','REJECTED'].includes(row.reviewStatus || '')" @click="submit(row)" :disabled="busy">提交审核</button><button @click="openWindows(row)" :disabled="busy">共享窗口</button></template>
 <template v-if="section==='reviews'"><template v-if="row.reviewStatus==='PENDING'"><button @click="review(row,'APPROVE')" :disabled="busy">通过</button><button @click="review(row,'REJECT')" :disabled="busy">驳回</button></template><button @click="ban(row)" :disabled="busy">{{row.enabled?'封禁':'恢复'}}</button></template></td></tr></tbody></table><p v-if="!rows.length&&!busy" class="empty">暂无记录</p></div>
 <div class="pagination"><button :disabled="busy||page===1" @click="page--;run(load)">上一页</button><span>第 {{page}} 页 · 共 {{total}} 条</span><button :disabled="busy||page*20>=total" @click="page++;run(load)">下一页</button></div>
 <form v-if="formOpen" class="panel manage-form" @submit.prevent="save"><h2>{{editing?'编辑':'新增'}}{{titles[section]}}</h2><template v-if="section==='users'"><p>授权变更后，该用户须重新登录。</p><label v-for="r in ['DRIVER','OWNER','PROPERTY','PLATFORM']" :key="r" class="check"><input type="checkbox" :value="r" v-model="roles">{{r}}</label></template><template v-else>
 <label v-if="section==='owner'&&!editing">所属社区<select v-model="form.communityId" required><option v-for="c in communities" :value="c.id" :key="c.id">{{c.name}}</option></select></label>
 <label v-for="field in fields" :key="field[0]">{{field[1]}}<input v-model="form[field[0]!]" required :maxlength="field[0]==='proofText'?1000:field[0]==='plate'?20:field[0]==='spaceCode'?60:255"></label>
 <label v-if="section==='owner'">车位类型<select v-model="form.spaceType"><option value="STANDARD">标准车位</option><option value="COMPACT">小型车位</option><option value="EV">新能源车位</option></select></label></template>
 <button class="primary" :disabled="busy||unknownWrite">保存</button> <button type="button" @click="formOpen=false" :disabled="busy">关闭表单</button></form>
 <form v-if="authCommunity" class="panel manage-form" @submit.prevent="saveAuthorization"><h2>社区 {{authCommunity}} 的物业授权</h2><p v-if="!authorizationReady">授权详情尚未成功读取，请刷新管理列表后重试。</p><p>仅显示启用的物业账号。清空选择表示撤销全部授权。</p>
 <label>搜索物业用户<input v-model="candidateQuery" maxlength="40"></label><button type="button" @click="candidatePage=1;run(loadCandidates)" :disabled="busy">搜索物业</button>
 <label v-for="u in candidates" :key="u.id" class="check"><input type="checkbox" :value="u.id" v-model="authUsers">{{u.username}}（{{u.id}}）</label>
 <p v-if="!candidates.length">暂无符合条件的物业账号</p><p>已选：<span v-for="id in authUsers" :key="id">{{selectedNames[id]||id}} <button type="button" @click="authUsers=authUsers.filter(v=>v!==id)" :aria-label="'移除 '+(selectedNames[id]||id)">移除</button> </span></p>
 <div class="pagination"><button type="button" :disabled="busy||candidatePage===1" @click="candidatePage--;run(loadCandidates)">上一页物业</button><span>{{candidatePage}} · 共 {{candidateTotal}} 条</span><button type="button" :disabled="busy||candidatePage*20>=candidateTotal" @click="candidatePage++;run(loadCandidates)">下一页物业</button></div>
 <button class="primary" :disabled="busy||unknownWrite||!authorizationReady">保存物业授权</button><button type="button" @click="authCommunity=''" :disabled="busy">关闭</button></form>
 <section v-if="selectedSpace" class="panel manage-form"><h2>{{selectedSpace.name}} · 单次共享窗口</h2><p>北京时间；未来30天，15分钟对齐。有有效预约的窗口不能修改或停用。</p><div class="window-calendar"><article v-for="w in windowRows" :key="w.id" :class="{disabled:!w.enabled}"><b>{{businessDate(w.startAt)}}</b><span>至 {{businessDate(w.endAt)}}</span><span>¥{{(w.hourlyFen/100).toFixed(2)}} / 小时 · {{w.enabled?'启用':'停用'}}</span><button @click="editWindow(w)" :disabled="busy">编辑窗口</button><button @click="toggleWindow(w)" :disabled="busy">{{w.enabled?'停用窗口':'启用窗口'}}</button></article></div><p v-if="!windowsReady">窗口详情尚未成功读取，请刷新管理列表后重试。</p><p v-else-if="!windowRows.length">暂无窗口</p>
 <div class="pagination"><button :disabled="busy||windowPage===1" @click="windowPage--;run(loadWindows)">上一页窗口</button><span>{{windowPage}} · 共 {{windowTotal}} 条</span><button :disabled="busy||windowPage*20>=windowTotal" @click="windowPage++;run(loadWindows)">下一页窗口</button></div>
 <form @submit.prevent="saveWindow"><h3>{{windowEditing?'修改窗口':'新增窗口'}}</h3><label>窗口开始<input type="datetime-local" step="900" v-model="windowForm.startAt" required></label><label>窗口结束<input type="datetime-local" step="900" v-model="windowForm.endAt" required></label><label>小时价格（元／小时）<input type="text" inputmode="decimal" v-model="windowForm.hourlyYuan" required></label><button class="primary" :disabled="busy||unknownWrite||!windowsReady">保存窗口</button><button v-if="windowEditing" type="button" @click="windowEditing=null">改为新增</button></form><button @click="selectedSpace=null" :disabled="busy">关闭窗口管理</button></section>
 </section>
</template>
<style scoped>
.manage-tools,.pagination{display:flex;align-items:center;gap:12px;margin:18px 0;flex-wrap:wrap}.manage-form{margin-top:24px}.manage-form label{margin:12px 0}.manage-form button{margin:8px 8px 0 0}.check{display:flex;align-items:center;gap:8px}.check input{width:auto}.audit-detail{max-width:700px;overflow-wrap:anywhere}.window-calendar{display:grid;grid-template-columns:repeat(auto-fill,minmax(240px,1fr));gap:12px}.window-calendar article{border:1px solid #cbd9d3;border-left:4px solid #186b50;border-radius:8px;padding:14px}.window-calendar span{display:block}.window-calendar .disabled{opacity:.65;border-left-color:#999}
</style>



