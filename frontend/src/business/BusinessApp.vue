<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { api, setToken, ApiError } from './api'
import ResourcePanel from './ResourcePanel.vue'
import { yuanToFen } from './money'
import { router, paths } from './router'
import { businessDate, businessInput, businessInstant } from './business-time'
import { readPending, savePending, type PendingParking } from './pending-parking'
import type { User, Community, Vehicle, Space, Reservation, Bill, SearchCriteria } from './types'
const user = ref<User | null>(null), username = ref(''), password = ref('')
const page = ref('spaces'), busy = ref(false), message = ref(''), error = ref(false)
const registering=ref(false), spaceType=ref(''), maxHourlyFen=ref(''), searchPage=ref(1), searchTotal=ref(0)
const spaces = ref<Space[]>([]), vehicles = ref<Vehicle[]>([]), orders = ref<Reservation[]>([]), communities = ref<Community[]>([])
const communityId = ref('10'), vehicleId = ref(''), startAt = ref(''), endAt = ref('')
const propertyCommunities = ref<Community[]>([]), propertyCommunityId = ref('')
const ordersLoading = ref(false)
const pendingParking = ref<PendingParking | null>(null), recoveryStorage = ref(true)
const closeNames: Record<string,string> = { USER_CANCELLED:'司机主动取消', PAYMENT_TIMEOUT:'付款超时', DRIVER_NO_SHOW:'司机未到场', SPACE_UNAVAILABLE:'车位无法履约' }
function remaining(order: Reservation) { const seconds=Math.max(0,Math.ceil((Date.parse(order.payDeadline)-businessNow.value)/1000));return Number.isFinite(seconds)?`${Math.floor(seconds/60)}分${seconds%60}秒`:'正在核对' }
function setPending(value: PendingParking | null) { const completed=pendingParking.value;recoveryStorage.value=savePending(value,completed);pendingParking.value=value ?? (user.value?readPending(user.value.id):null) }
function definiteFailure(e: unknown) { return e instanceof ApiError && e.status>=400 && e.status<500 && ![401,429].includes(e.status) && !['REQUEST_IN_PROGRESS','STALE_SESSION','IDEMPOTENCY_CONFLICT'].includes(e.code) }
async function submitPending(operation: PendingParking) {
 try { const result=await api<Reservation>(operation.path,operation.body,operation.key);setPending(null);return result }
 catch(e) { if(definiteFailure(e))setPending(null);throw e }
}
async function recoverParking(retry=false) {
 await run(async()=>{
  const operation=pendingParking.value;if(!operation||operation.account!==user.value?.id)return
  let result: Reservation
  if(operation.kind==='reserve'&&!retry){
   try{result=await api<Reservation>('/reservations/by-request-key/'+encodeURIComponent(operation.key))}
   catch(e){if(e instanceof ApiError&&e.status===404){message.value='尚未查到已提交结果，不能确定原请求失败。可以继续核对或使用原请求重试。';return}throw e}
   setPending(null)
  }else result=await submitPending(operation)
  page.value=operation.kind==='reserve'?'orders':'property';const loaded=await loadOrders()
  if(loaded)message.value='原操作已核对，列表已刷新为当前状态'
  if(operation.kind==='reserve')selectedBill.value=result.reservationId
 })
}
let orderRequest = 0, resourceRequest = 0, timeRequest = 0
const bill = ref<Bill | null>(null), selectedBill = ref(''), now = ref(''), advanceAt = ref('')
const queried = ref<SearchCriteria | null>(null)
const communityName = computed(() => (page.value === 'property' ? propertyCommunities.value.find(c => c.id === propertyCommunityId.value) : communities.value.find(c => c.id === communityId.value))?.name || '请选择社区')
const clockSample = ref(0), clockTick = ref(0)
const businessNow = computed(() => now.value ? new Date(now.value).getTime() + Math.max(0, clockTick.value - clockSample.value) : NaN)
function canCancel(order: Reservation) { return ['WAIT_PAY', 'CONFIRMED'].includes(order.status) && businessNow.value < new Date(order.startAt).getTime() }
const aiBusy = ref(false), aiError = ref('')
watch([communityId, startAt, endAt], () => { spaces.value = []; queried.value = null }, { flush: 'sync' })
const currentBill = computed(() => bill.value?.reservationId === selectedBill.value)
const question = ref('怎样完成预约和停车？'), aiAnswer = ref('')
const keys = new Map<string, string>()
const role = computed(() => user.value?.roles?.join(' / ') || '')
const isDriver = computed(() => user.value?.roles.includes('DRIVER') || false)
const isOwner = computed(() => user.value?.roles.includes('OWNER') || false)
const isAdmin = computed(() => user.value?.roles.includes('PLATFORM') || false)
const isProperty = computed(() => user.value?.roles.some(r=>['PROPERTY','PLATFORM'].includes(r)) || false)
const managementPages=['vehicles','owner','reviews','communities','users','audit']
function allowed(target:string){if(['spaces','orders','bill','vehicles'].includes(target))return isDriver.value;if(target==='owner')return isOwner.value;if(['property','reviews'].includes(target))return isProperty.value;if(['demo','communities','users','audit'].includes(target))return isAdmin.value;return target==='ai'}
watch([spaceType,maxHourlyFen],()=>{spaces.value=[];queried.value=null;searchPage.value=1})
let requestedRoute='', routeGeneration=0
watch(page,value=>{if(paths[value]){requestedRoute=value;const generation=++routeGeneration;void router.replace(paths[value]!).finally(()=>{if(generation===routeGeneration)requestedRoute=''})}},{flush:'sync'})
watch(()=>router.currentRoute.value.name,name=>{const target=String(name||'');if(!user.value||requestedRoute)return;if(!allowed(target)){message.value='没有此页面的访问权限';void router.replace(paths[page.value]||'/assistant');return}if(target!==page.value)void changePage(target)})
const names: Record<string, string> = { WAIT_PAY: '待付款', CONFIRMED: '预约已确认', IN_USE: '停车中', COMPLETED: '已离场', EXPIRED: '已过期', CANCELLED: '已取消', NO_SHOW: '未到场', SPACE_UNAVAILABLE: '车位无法履约', CREATED: '等待处理', SUBMITTED: '确认中', UNKNOWN: '结果待确认', SUCCESS: '成功', REVIEW: '待人工核查' }
const money = (value: unknown) => '¥' + (Number(value || 0) / 100).toFixed(2)
const date = businessDate
const localInput = businessInput
function requestKey(operation: string, body: unknown) { const signature = operation + JSON.stringify(body); if (!keys.has(signature)) keys.set(signature, crypto.randomUUID()); return keys.get(signature)! }
function clearSession() { setToken(''); user.value = null; orderRequest++; resourceRequest++; ordersLoading.value=false; communities.value=[]; propertyCommunities.value=[]; propertyCommunityId.value=''; password.value = ''; vehicles.value = []; spaces.value = []; orders.value = []; bill.value = null; selectedBill.value = ''; aiAnswer.value = ''; aiError.value = ''; startAt.value = ''; endAt.value = ''; now.value = ''; advanceAt.value = ''; keys.clear() }
async function run(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = false; message.value = ''
  try { await work() } catch (e) {
    error.value = true
    if (e instanceof ApiError) { message.value = e.message; if (e.status === 401) clearSession(); if(e.code==='QUOTE_CHANGED'){spaces.value=[];queried.value=null} }
    else message.value = e instanceof Error ? e.message : '请求未完成，请刷新状态。'
  } finally { busy.value = false }
}
async function refreshTime() {
  const request=++timeRequest, session=user.value
  const result=await api<{ now: string }>('/demo/clock')
  if(request!==timeRequest||session!==user.value)return
  now.value = result.now
  clockSample.value = performance.now(); clockTick.value = clockSample.value
  if (!startAt.value) { const start = Math.ceil((new Date(now.value).getTime() + 1800000) / 900000) * 900000; startAt.value = localInput(start); endAt.value = localInput(start + 3600000) }
}
async function login() {
  await run(async () => {
    if(registering.value){await api('/auth/register',{username:username.value,password:password.value});registering.value=false;password.value='';message.value='注册成功，请登录。默认开通司机角色，业主角色由管理员授权。';return}
    const result = await api<{ accessToken: string; user: User }>('/auth/login', { username: username.value, password: password.value })
    setToken(result.accessToken); user.value = result.user; password.value = '';pendingParking.value=readPending(result.user.id)
    await loadCommunities()
    await refreshTime()
    if (isDriver.value) { vehicles.value = await api<Vehicle[]>('/vehicles'); vehicleId.value = vehicles.value[0]?.id || ''; page.value = 'spaces' }
    else if (isProperty.value) { page.value = 'property'; await loadOrders() }
    else page.value = isOwner.value?'owner':'ai'
    message.value = '登录成功'
  })
}
async function logout() { await run(async () => { try { await api('/auth/logout', {}) } finally { clearSession() } }) }
async function query(reset=true) {
  await run(async () => {
    if(reset)searchPage.value=1
    const criteria = { communityId: communityId.value, startAt: startAt.value, endAt: endAt.value }
    const filterSignature=spaceType.value+':'+maxHourlyFen.value
    spaces.value = []; queried.value = null
    const params = new URLSearchParams({ ...criteria, startAt: businessInstant(criteria.startAt), endAt: businessInstant(criteria.endAt) })
    params.set('page',String(searchPage.value));params.set('size','20');if(spaceType.value)params.set('spaceType',spaceType.value);if(maxHourlyFen.value)params.set('maxHourlyFen',String(yuanToFen(maxHourlyFen.value,true)))
    const result = await api<{items:Space[];total:number}>('/spaces/search?' + params)
    if (criteria.communityId !== communityId.value || criteria.startAt !== startAt.value || criteria.endAt !== endAt.value || filterSignature!==spaceType.value+':'+maxHourlyFen.value) return
    queried.value = criteria; spaces.value = result.items;searchTotal.value=result.total; message.value = spaces.value.length ? '找到可预约车位' : '当前时段暂无空位，请更换时间'
  })
}
async function reserve(space: Space) {
  await run(async () => {
    pendingParking.value=readPending(user.value!.id);if(pendingParking.value){message.value='请先核对尚未确认的原操作';return}
    const criteria = queried.value
    if (!criteria || !spaces.value.includes(space)) { message.value = '查询条件已变化，请重新查询'; return }
    const body = { vehicleId: vehicleId.value, spaceId: space.id, startAt: businessInstant(criteria.startAt), endAt: businessInstant(criteria.endAt),windowId:space.windowId,windowVersion:space.windowVersion,expectedHourlyFen:space.hourlyFen,expectedPrepayFen:space.estimatedFen }
    const operation: PendingParking={account:user.value!.id,kind:'reserve',key:crypto.randomUUID(),path:'/reservations',body};setPending(operation)
    const order = await submitPending(operation); message.value = '预约已创建，请在付款截止前完成模拟支付'
    bill.value = null; spaces.value = []; queried.value = null
    page.value = 'orders'; selectedBill.value = order.reservationId; await loadOrders()
  })
}
async function loadOrders() {
  const request=++orderRequest, session=user.value, target=page.value, community=propertyCommunityId.value
  const current=()=>request===orderRequest&&session===user.value&&target===page.value&&(target!=='property'||community===propertyCommunityId.value)
  orders.value=[];ordersLoading.value=true
  try {
    if(target==='property'&&!community)return false
    const [result]=await Promise.all([api<Reservation[]>(target==='property'?'/property/reservations?communityId='+community:'/reservations'),refreshTime()])
    if(current()){orders.value=result;return true}
  } catch(e) {
    if(current()){error.value=true;message.value=e instanceof Error?e.message:'订单加载失败';if(e instanceof ApiError&&e.status===401)clearSession()}
  } finally {if(request===orderRequest)ordersLoading.value=false}
  return false
}
watch(propertyCommunityId,()=>{if(page.value==='property')void loadOrders()},{flush:'sync'})
watch(page,()=>{orderRequest++;orders.value=[];ordersLoading.value=false},{flush:'sync'})
async function showBill(id: string) {
  if (busy.value) return
  bill.value = null
  selectedBill.value = id; page.value = 'bill'
  await run(() => refreshBill(id))
}
async function refreshBill(id: string) {
  try {
    const result = await api<Bill>('/bills/' + id)
    if (selectedBill.value === id && result.reservationId === id) bill.value = result
  } catch (e) { if (selectedBill.value === id) bill.value = null; throw e }
}
async function pay(kind: string) {
  await run(async () => {
    if (!currentBill.value || !bill.value) { message.value = '请先加载当前订单账单'; return }
    const body = { reservationId: bill.value.reservationId, kind }
    await api('/payments', body, requestKey('pay', body))
    message.value = '请求已受理，结果正在确认。请刷新账单，不要重复发起付款。'
    await refreshBill(selectedBill.value)
  })
}
async function cancel(id: string) {
  await run(async () => {
    await refreshTime()
    const order = orders.value.find(o => o.reservationId === id)
    if (!order || !canCancel(order)) { message.value = '预约已开始或状态已变化，不能取消'; return }
    if (!window.confirm('确认取消此预约？已付款项将异步退款，退款结果请查看账单。')) return
    await api('/reservations/' + id + '/cancel', {}); if(await loadOrders())message.value = '预约已关闭；若已付款，退款将异步处理'
  })
}
async function gate(id: string, action: string) { await run(async () => {
 pendingParking.value=readPending(user.value!.id);if(pendingParking.value){message.value='请先核对尚未确认的原操作';return}
 const operation: PendingParking={account:user.value!.id,kind:'gate',key:crypto.randomUUID(),path:'/property/reservations/'+id+'/'+action,body:{}};setPending(operation)
 try{await submitPending(operation)}catch(e){if(e instanceof ApiError&&e.code==='SPACE_OCCUPIED')await loadOrders();throw e}
 if(await loadOrders())message.value = action === 'entry' ? '入场成功' : '离场成功，费用将同步到账单'
 }) }
async function unavailable(id:string){await run(async()=>{
 pendingParking.value=readPending(user.value!.id);if(pendingParking.value){message.value='请先核对尚未确认的原操作';return}
 const reason=window.prompt('请输入无法履约原因（最多500字）');if(reason===null)return
 if(!reason.trim()||reason.trim().length>500){message.value='原因不能为空且最多500字';return}
 if(!window.confirm('确认预约 '+id+' 无法履约？订单将关闭，已收款项异步退款。'))return
 const operation: PendingParking={account:user.value!.id,kind:'unavailable',key:crypto.randomUUID(),path:'/property/reservations/'+id+'/unavailable',body:{reason:reason.trim()}};setPending(operation)
 await submitPending(operation);if(await loadOrders())message.value='已确认无法履约，已收款项将异步退款'
})}
async function changePage(target: string) {
  if(!allowed(target)){message.value='没有此页面的访问权限';return}
  page.value = target; message.value = ''
  if(target==='orders'||target==='property'){await loadOrders();return}
  await run(async () => {if(target==='spaces'&&isDriver.value){vehicles.value=await api<Vehicle[]>('/vehicles');if(!vehicles.value.some(v=>v.id===vehicleId.value))vehicleId.value=vehicles.value[0]?.id||''} await refreshTime() })
}
async function loadCommunities(){
  const session=user.value, request=++resourceRequest
  const [publicRows,propertyRows]=await Promise.all([api<Community[]>('/communities'),isProperty.value?api<Community[]>('/property/communities'):Promise.resolve([])])
  if(session!==user.value||request!==resourceRequest)return
  communities.value=publicRows;propertyCommunities.value=propertyRows
  if(!publicRows.some(c=>c.id===communityId.value))communityId.value=publicRows[0]?.id||''
  if(!propertyRows.some(c=>c.id===propertyCommunityId.value))propertyCommunityId.value=propertyRows[0]?.id||''
}
async function refreshResources(){const session=user.value;try{await loadCommunities();if(user.value!==session)return;if(isDriver.value){const updatedVehicles=await api<Vehicle[]>('/vehicles');if(user.value===session)vehicles.value=updatedVehicles}}catch(e){if(user.value===session&&e instanceof ApiError){message.value=e.message;if(e.status===401)clearSession()}}}
async function advance() { await run(async () => { await api('/demo/clock', { now: businessInstant(advanceAt.value) }); await refreshTime(); message.value = '演示时间已推进。此操作会触发待付款过期和未到场检查。' }) }
async function ask() {
  if (aiBusy.value) return
  aiBusy.value = true; aiError.value = ''; const session = user.value
  try {
    const result = await api<{ answer: string }>('/ai/prototype', { question: question.value })
    if (user.value === session) aiAnswer.value = result.answer
  } catch (e) {
    if (user.value === session) {
      aiError.value = e instanceof Error ? e.message : '助手暂不可用'
      if (e instanceof ApiError && e.status === 401) clearSession()
    }
  } finally { aiBusy.value = false }
}
const activePayment = computed(() => bill.value?.attempts?.some((p) => ['CREATED', 'SUBMITTED', 'UNKNOWN', 'REVIEW'].includes(p.status)))
const closedBill = computed(() => ['CANCELLED', 'EXPIRED', 'NO_SHOW', 'SPACE_UNAVAILABLE'].includes(bill.value?.status || ''))
let timer: ReturnType<typeof setInterval> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
function refreshOnFocus(){if(user.value&&!busy.value)void run(async()=>{if(['orders','property'].includes(page.value))await loadOrders();else await refreshTime()})}
onMounted(() => { window.addEventListener('focus',refreshOnFocus);clockTimer = setInterval(() => { clockTick.value = performance.now() }, 1000); timer = setInterval(() => {
  if (user.value && !busy.value && page.value === 'bill' && selectedBill.value) {
    void run(async () => { await refreshBill(selectedBill.value) })
  }
}, 5000) })
onUnmounted(() => { window.removeEventListener('focus',refreshOnFocus);if (timer) clearInterval(timer); if (clockTimer) clearInterval(clockTimer) })
</script>

<template>
  <div class="parking-app">
    <header class="topbar"><div><span class="brand-mark">P</span><strong>悦享泊</strong><small>共享邻里车位，悦享便捷停车。</small></div><span class="demo-tag">本地演示 · 模拟支付</span></header>
    <main v-if="!user" class="login-layout">
      <section class="welcome"><p class="eyebrow">悦享泊——智慧社区车位共享与结算平台</p><h1>让闲置车位<br>连接每一次归来。</h1><p>查询共享时段，预约心仪车位。<br>从入场到结账，一笔账单清晰记录。</p><div class="steps">01 选位　 →　 02 预约　 →　 03 停车</div></section>
      <form class="panel login" @submit.prevent="login"><h2>{{registering ? "注册账号" : "欢迎登录"}}</h2><p class="muted">注册后可管理车辆、查询和预约车位。</p><label>账号<input v-model="username" autocomplete="username" required placeholder="driver / property / admin"></label><label>密码<input v-model="password" type="password" autocomplete="current-password" required></label><button class="primary" :disabled="busy">{{ busy ? '处理中…' : registering ? '注册' : '登录' }}</button><button type="button" @click="registering=!registering" :disabled="busy">{{registering ? "返回登录" : "创建新账号"}}</button><p class="hint">新账号4至40位字母数字下划线，密码至少10字符。演示账号仍可使用。</p><p v-if="message" role="alert" class="error">{{ message }}</p></form>
    </main>
    <template v-else>
      <div class="workspace"><aside><div class="profile"><b>{{ user.username }}</b><small>{{ role }}</small></div><nav>
        <button v-if="isDriver" :class="{ active: page === 'spaces' }" @click="changePage('spaces')" :disabled="busy">查询与预约</button>
        <button v-if="isDriver" :class="{ active: page === 'orders' || page === 'bill' }" @click="changePage('orders')" :disabled="busy">我的预约与账单</button>
        <button v-if="isProperty" :class="{ active: page === 'property' }" @click="changePage('property')" :disabled="busy">物业入离场</button>
        <button v-if="isDriver" @click="changePage('vehicles')" :class="{active:page==='vehicles'}" :disabled="busy">车辆管理</button>
        <button v-if="isOwner" @click="changePage('owner')" :class="{active:page==='owner'}" :disabled="busy">业主车位管理</button>
        <button v-if="isProperty" @click="changePage('reviews')" :class="{active:page==='reviews'}" :disabled="busy">车位审核</button>
        <button v-if="isAdmin" @click="changePage('communities')" :class="{active:page==='communities'}" :disabled="busy">社区管理</button>
        <button v-if="isAdmin" @click="changePage('users')" :class="{active:page==='users'}" :disabled="busy">用户与角色</button>
        <button v-if="isAdmin" @click="changePage('audit')" :class="{active:page==='audit'}" :disabled="busy">操作审计</button>
        <button :class="{ active: page === 'ai' }" @click="changePage('ai')" :disabled="busy">停车助手 · 模型桩</button>
        <button v-if="isAdmin" :class="{ active: page === 'demo' }" @click="changePage('demo')" :disabled="busy">演示时间</button>
      </nav><button class="logout" @click="logout" :disabled="busy">退出登录</button></aside>
      <section class="content"><div class="context"><span>{{ communityName }}</span><span>演示时间 {{ date(now) }}</span></div>
        <p v-if="message" role="status" :class="['notice', { error }]">{{ message }}</p>
        <section v-if="pendingParking && pendingParking.account===user.id" class="notice" aria-label="待核对操作"><p>有一笔操作结果待确认，请先核对原请求。</p><button :disabled="busy" @click="recoverParking()">核对原操作</button><button v-if="pendingParking.kind==='reserve'" :disabled="busy" @click="recoverParking(true)">使用原请求重试</button></section>
        <p v-if="!recoveryStorage" class="notice">浏览器无法保存恢复记录；当前页面仍保留原请求，请在核对完成前不要刷新或关闭页面。</p>
        <ResourcePanel v-if="managementPages.includes(page)" :key="page" :section="page" :user="user" :communities="page==='reviews'?propertyCommunities:communities" :now="now" @expired="clearSession" @changed="refreshResources" />
        <template v-if="page === 'spaces'">
          <h1>找到这一程的车位</h1><p class="muted">时间统一为北京时间（Asia/Shanghai）。提前至少15分钟预约，时间按15分钟对齐；单次30分钟至24小时。</p>
          <form class="panel filters" @submit.prevent="query()">
            <label>社区<select v-model="communityId"><option v-for="c in communities" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            <label>开始时间<input v-model="startAt" type="datetime-local" step="900" required></label><label>结束时间<input v-model="endAt" type="datetime-local" step="900" required></label>
            <label>本人车辆<select v-model="vehicleId" required><option v-for="v in vehicles" :key="v.id" :value="v.id">{{ v.plate }}</option></select></label>
            <label>车位类型<select v-model="spaceType"><option value="">全部</option><option value="STANDARD">标准车位</option><option value="COMPACT">小型车位</option><option value="EV">新能源车位</option></select></label>
            <label>最高小时价格（元／小时）<input v-model="maxHourlyFen" type="text" inputmode="decimal" placeholder="不限"></label>
            <button class="primary" :disabled="busy">查询车位</button>
          </form>
          <div class="space-grid"><article v-for="s in spaces" :key="s.id" class="panel space"><span class="available">可预约</span><h2>{{ s.name }}</h2><p class="muted">{{ communityName }} · 共享车位</p><div class="price">{{ money(s.hourlyFen) }}<small> / 小时</small></div><p>本次预计 {{ money(s.estimatedFen) }}</p><button class="primary" :disabled="busy || !!pendingParking || !vehicleId || !queried" @click="reserve(s)">预约此车位</button></article></div>
          <p v-if="!spaces.length" class="empty">选择社区和时间，查询当前可预约车位。</p>
          <div class="pagination"><button :disabled="busy||searchPage===1" @click="searchPage--;query(false)">上一页车位</button><span>第 {{searchPage}} 页 · 共 {{searchTotal}} 条</span><button :disabled="busy||searchPage*20>=searchTotal" @click="searchPage++;query(false)">下一页车位</button></div>
        </template>
        <template v-if="page === 'orders' || page === 'property'">
          <label v-if="page==='property'">履约社区<select v-model="propertyCommunityId"><option v-for="c in propertyCommunities" :key="c.id" :value="c.id">{{c.name}}</option></select></label>
          <div class="title-row"><h1>{{ page === 'property' ? '物业入离场工作台' : '我的预约' }}</h1><button @click="loadOrders" :disabled="busy||ordersLoading">刷新列表</button></div>
          <p v-if="ordersLoading" role="status">正在加载预约…</p>
          <div class="panel table-wrap"><table><thead><tr><th>预约 / 车辆</th><th>时段</th><th>状态</th><th>预计金额</th><th>操作</th></tr></thead><tbody>
            <tr v-for="o in orders" :key="o.reservationId"><td><code>{{ o.reservationId }}</code><br>{{ o.plate }}</td><td>{{ date(o.startAt) }}<br>{{ date(o.endAt) }}</td><td><span class="status">{{ names[o.status] || o.status }}</span><small v-if="o.status === 'WAIT_PAY'">付款截止 {{ date(o.payDeadline) }} · 剩余 {{ remaining(o) }}</small><small v-if="o.closeReason">{{ closeNames[o.closeReason] || o.closeReason }}</small><small v-if="o.entryBlockedAt">曾于 {{date(o.entryBlockedAt)}} 到场受阻</small></td><td>{{ money(o.prepayFen) }}</td><td class="actions">
              <template v-if="page === 'orders'"><button @click="showBill(o.reservationId)" :disabled="busy">查看账单</button><button v-if="['WAIT_PAY', 'CONFIRMED'].includes(o.status)" @click="cancel(o.reservationId)" :disabled="busy || !canCancel(o)" :title="canCancel(o) ? '预约开始前可取消' : '预约已开始，不能自主取消'">取消预约</button></template>
              <template v-else><button v-if="o.status === 'CONFIRMED'" @click="gate(o.reservationId, 'entry')" :disabled="busy || !!pendingParking">{{o.entryBlockedAt?'重新尝试入场':'办理入场'}}</button><button v-if="o.status === 'IN_USE'" @click="gate(o.reservationId, 'exit')" :disabled="busy || !!pendingParking">办理离场</button><button v-if="['WAIT_PAY','CONFIRMED'].includes(o.status)" @click="unavailable(o.reservationId)" :disabled="busy || !!pendingParking">确认无法履约</button></template>
            </td></tr></tbody></table><p v-if="!orders.length" class="empty">暂无预约记录</p></div>
        </template>
        <template v-if="page === 'bill'">
          <div class="title-row"><h1>停车账单</h1><button @click="showBill(selectedBill)" :disabled="busy">刷新账单</button></div>
          <p class="muted">预约号 {{ selectedBill }} · 金额以后台账单为准，每5秒刷新状态。</p>
          <section v-if="bill && currentBill" class="panel bill"><span class="status">{{ names[bill.status] }}</span><div class="amounts"><div><small>预约费用</small><b>{{ money(bill.prepayFen) }}</b></div><div><small>超时费用</small><b>{{ money(bill.overtimeFen) }}</b></div><div><small>已付合计</small><b>{{ money(Number(bill.paidPrepayFen) + Number(bill.paidOvertimeFen)) }}</b></div><div><small>已退金额</small><b>{{ money(bill.refundFen) }}</b></div></div>
            <p v-if="activePayment" class="notice">付款或退款正在确认；标记待人工核查时请联系管理员，不要重复付款。</p>
            <p v-if="bill.status==='WAIT_PAY' && bill.paidPrepayFen>=bill.prepayFen" class="notice">已收款，预约确认中。请以预约确认结果为准。</p>
            <p v-if="closedBill && bill.refundFen < bill.paidPrepayFen + bill.paidOvertimeFen" class="notice">预约已关闭，退款待处理或确认中，请查看交易记录。</p>
            <button v-if="!closedBill && Number(bill.paidPrepayFen) < Number(bill.prepayFen)" class="primary" :disabled="busy || activePayment" @click="pay('PREPAY')">模拟支付预约费</button>
            <button v-if="!closedBill && Number(bill.paidOvertimeFen) < Number(bill.overtimeFen)" class="primary" :disabled="busy || activePayment" @click="pay('OVERTIME')">模拟支付超时费</button>
            <h3>交易记录</h3><p v-for="p in bill.attempts" :key="p.id">{{ p.kind === 'REFUND' ? '退款' : '付款' }} · {{ money(p.amountFen) }} · {{ names[p.status] || p.status }}</p><p v-if="!bill.attempts.length" class="muted">暂无交易记录</p><p class="hint">仅演示模拟收退款，不发生真实扣款。业主分账与正式结算将在后续阶段实现。</p>
          </section><p v-else class="empty">账单准备中或查询失败，请稍后刷新。</p>
        </template>
        <template v-if="page === 'ai'"><h1>停车助手</h1><p class="notice">当前为模型桩，尚未接入真实模型，也不代表真实 AI 能力验收。</p><form class="panel assistant" @submit.prevent="ask"><label>想了解什么？<textarea v-model="question" maxlength="500" required rows="3"></textarea></label><button class="primary" :disabled="aiBusy">{{ aiBusy ? '回答中…' : '发送问题' }}</button><p v-if="aiError" role="alert" class="error">{{ aiError }}</p><p v-if="aiAnswer" class="answer">{{ aiAnswer }}</p><p class="hint">助手不执行预约、支付或退款。请通过对应页面操作。</p></form></template>
        <template v-if="page === 'demo'"><h1>本地演示时间</h1><section class="panel"><p>业务时钟仅用于演示，登录令牌仍按真实时间过期。推进时钟会影响所有演示订单，无法回退。</p><label>推进到<input v-model="advanceAt" type="datetime-local" required></label><button class="primary" :disabled="busy || !advanceAt" @click="advance">推进演示时间</button><p class="hint">入场演示：推进到预约开始；离场演示：推进到结束后，再切换物业账号办理。</p></section></template>
      </section></div>
    </template>
    <footer>悦享泊——智慧社区车位共享与结算平台 · 毕业设计业务演示</footer>
  </div>
</template>
<style>
*{box-sizing:border-box}body{margin:0;color:#19342e;background:#f4f7f4;font:15px/1.6 "Microsoft YaHei",sans-serif}button,input,select,textarea{font:inherit}button{cursor:pointer;border:1px solid #cbd9d3;background:white;color:#254d40;border-radius:8px;padding:9px 15px}button:disabled{opacity:.5;cursor:wait}button:hover:not(:disabled){border-color:#18765a;background:#eef7f1}.primary{background:#186b50;color:white;border-color:#186b50}.primary:hover:not(:disabled){background:#135740;color:white}input,select,textarea{display:block;width:100%;border:1px solid #c8d4ce;border-radius:7px;padding:10px;background:white;color:#19342e}label{display:block;font-size:13px;font-weight:600}input:focus,select:focus,textarea:focus{outline:2px solid #63a88c;outline-offset:2px}.topbar{height:86px;background:white;border-bottom:1px solid #dde7e0;padding:0 5%;display:flex;align-items:center;justify-content:space-between}.topbar>div{display:flex;align-items:center;gap:14px}.topbar small{color:#7b8c84}.brand-mark{background:#186b50;color:white;width:40px;height:40px;text-align:center;line-height:40px;border-radius:11px;font-size:24px;font-weight:bold}.demo-tag{font-size:12px;background:#f0e7cf;color:#806522;padding:5px 10px;border-radius:20px}.login-layout{max-width:1200px;margin:70px auto;display:grid;grid-template-columns:1.3fr 1fr;gap:90px;padding:0 30px;align-items:center}.welcome{padding:28px}.eyebrow{letter-spacing:3px;font-size:12px;color:#36866b}.welcome h1{font-size:45px;line-height:1.5;letter-spacing:2px}.welcome p{color:#658077}.steps{margin-top:40px;padding:25px 0;border-top:1px solid #c9d9cf;color:#367157}.panel{background:white;border:1px solid #dce5df;border-radius:14px;padding:28px;box-shadow:0 6px 24px #284c3a06}.login{padding:36px}.login label{margin:20px 0}.login button{width:100%}.muted,.hint{color:#718178}.hint{font-size:12px;margin-top:22px}.workspace{display:grid;grid-template-columns:225px 1fr;min-height:75vh}aside{background:#edf3ee;border-right:1px solid #dce5df;padding:28px 18px}.profile{padding:12px 12px 25px}.profile small{display:block;color:#72857b}nav button{display:block;width:100%;text-align:left;border:0;background:transparent;margin-bottom:8px;padding:12px}.active{background:#d7e9dd!important;color:#145839!important;font-weight:bold}.logout{margin-top:30px}.content{padding:25px 40px 60px;min-width:0}.context{display:flex;justify-content:space-between;color:#6a8174;font-size:12px;border-bottom:1px solid #dae4dd;padding-bottom:16px}.content h1{font-size:28px;margin-bottom:8px}.filters{display:flex;align-items:end;gap:16px;flex-wrap:wrap;margin:28px 0}.filters label{flex:1;min-width:170px}.space-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:20px}.space .primary{width:100%}.available,.status{display:inline-block;background:#e8f4eb;color:#256d49;border-radius:5px;padding:3px 9px;font-size:12px}.space h2{font-size:24px}.price{font-size:30px;font-weight:600}.price small{font-size:13px;font-weight:normal;color:#708377}.empty{text-align:center;padding:38px;color:#899a90}.notice{background:#e7f2ea;padding:12px 16px;border-radius:8px;color:#285e43}.error{color:#a13838;background:#fff0eb;padding:12px;border-radius:8px}.title-row{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}.table-wrap{overflow:auto;padding:12px 20px}table{border-collapse:collapse;width:100%;text-align:left;font-size:13px}td,th{padding:18px 10px;border-bottom:1px solid #e4eae5}th{color:#7a8b81;font-weight:normal}td small{display:block;font-size:11px;color:#7f8f85;max-width:180px}code{font-size:11px}.actions button{margin:3px;font-size:12px}.amounts{display:grid;grid-template-columns:repeat(4,1fr);gap:22px;padding:30px 0}.amounts small{display:block;color:#77897e}.amounts b{font-size:26px}.bill h3{margin-top:30px;border-top:1px solid #e3eae5;padding-top:25px}.assistant{max-width:800px}.assistant button{margin-top:20px}.answer{padding:25px;background:#f3f8f4;border-radius:8px}.content label{max-width:600px}.content>.panel>.primary{margin-top:20px}footer{text-align:center;color:#90a096;padding:25px;font-size:12px}@media(max-width:900px){.login-layout{grid-template-columns:1fr;margin:25px auto;gap:20px}.welcome h1{font-size:32px}.welcome{padding:0}.topbar small{display:none}.workspace{grid-template-columns:1fr}aside{border-right:0;display:flex;flex-wrap:wrap;gap:10px;padding:12px}.profile{padding:0}nav{display:flex;flex-wrap:wrap}nav button{width:auto;margin:0}.logout{margin:0}.content{padding:20px}.amounts{grid-template-columns:repeat(2,1fr)}.context{gap:20px}}
</style>


