<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { api, setToken, ApiError } from './api'
import { businessDate, businessInput, businessInstant } from './business-time'
import type { User, Community, Vehicle, Space, Reservation, Bill, SearchCriteria } from './types'
const user = ref<User | null>(null), username = ref(''), password = ref('')
const page = ref('spaces'), busy = ref(false), message = ref(''), error = ref(false)
const spaces = ref<Space[]>([]), vehicles = ref<Vehicle[]>([]), orders = ref<Reservation[]>([]), communities = ref<Community[]>([])
const communityId = ref('10'), vehicleId = ref(''), startAt = ref(''), endAt = ref('')
const bill = ref<Bill | null>(null), selectedBill = ref(''), now = ref(''), advanceAt = ref('')
const queried = ref<SearchCriteria | null>(null)
const communityName = computed(() => communities.value.find(c => c.id === communityId.value)?.name || '请选择社区')
const clockSample = ref(0), clockTick = ref(0)
const businessNow = computed(() => now.value ? new Date(now.value).getTime() + Math.max(0, clockTick.value - clockSample.value) : NaN)
function canCancel(order: Reservation) { return ['WAIT_PAY', 'CONFIRMED'].includes(order.status) && businessNow.value < new Date(order.startAt).getTime() }
const aiBusy = ref(false), aiError = ref('')
watch([communityId, startAt, endAt], () => { spaces.value = []; queried.value = null }, { flush: 'sync' })
const currentBill = computed(() => bill.value?.reservationId === selectedBill.value)
const question = ref('怎样完成预约和停车？'), aiAnswer = ref('')
const keys = new Map<string, string>()
const role = computed(() => user.value?.roles?.[0] || '')
const isDriver = computed(() => role.value === 'DRIVER')
const isProperty = computed(() => ['PROPERTY', 'PLATFORM'].includes(role.value))
const names: Record<string, string> = { WAIT_PAY: '待付款', CONFIRMED: '预约已确认', IN_USE: '停车中', COMPLETED: '已离场', EXPIRED: '已过期', CANCELLED: '已取消', NO_SHOW: '未到场', SPACE_UNAVAILABLE: '车位无法履约', CREATED: '等待处理', SUBMITTED: '确认中', UNKNOWN: '结果待确认', SUCCESS: '成功', REVIEW: '待人工核查' }
const money = (value: unknown) => '¥' + (Number(value || 0) / 100).toFixed(2)
const date = businessDate
const localInput = businessInput
function requestKey(operation: string, body: unknown) { const signature = operation + JSON.stringify(body); if (!keys.has(signature)) keys.set(signature, crypto.randomUUID()); return keys.get(signature)! }
function clearSession() { setToken(''); user.value = null; password.value = ''; vehicles.value = []; spaces.value = []; orders.value = []; bill.value = null; selectedBill.value = ''; aiAnswer.value = ''; aiError.value = ''; startAt.value = ''; endAt.value = ''; now.value = ''; advanceAt.value = ''; keys.clear() }
async function run(work: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = false; message.value = ''
  try { await work() } catch (e) {
    error.value = true
    if (e instanceof ApiError) { message.value = e.message; if (e.status === 401) clearSession() }
    else message.value = '网络中断或服务未就绪。请刷新状态，写操作重试将沿用原请求键。'
  } finally { busy.value = false }
}
async function refreshTime() {
  now.value = (await api<{ now: string }>('/demo/clock')).now
  clockSample.value = performance.now(); clockTick.value = clockSample.value
  if (!startAt.value) { const start = Math.ceil((new Date(now.value).getTime() + 1800000) / 900000) * 900000; startAt.value = localInput(start); endAt.value = localInput(start + 3600000) }
}
async function login() {
  await run(async () => {
    const result = await api<{ accessToken: string; user: User }>('/auth/login', { username: username.value, password: password.value })
    setToken(result.accessToken); user.value = result.user; password.value = ''
    communities.value = await api<Community[]>('/communities')
    await refreshTime()
    if (isDriver.value) { vehicles.value = await api<Vehicle[]>('/vehicles'); vehicleId.value = vehicles.value[0]?.id || ''; page.value = 'spaces' }
    else if (isProperty.value) { page.value = 'property'; await loadOrders() }
    else page.value = 'ai'
    message.value = '登录成功'
  })
}
async function logout() { await run(async () => { try { await api('/auth/logout', {}) } finally { clearSession() } }) }
async function query() {
  await run(async () => {
    const criteria = { communityId: communityId.value, startAt: startAt.value, endAt: endAt.value }
    spaces.value = []; queried.value = null
    const params = new URLSearchParams({ ...criteria, startAt: businessInstant(criteria.startAt), endAt: businessInstant(criteria.endAt) })
    const result = await api<Space[]>('/spaces/available?' + params)
    if (criteria.communityId !== communityId.value || criteria.startAt !== startAt.value || criteria.endAt !== endAt.value) return
    queried.value = criteria; spaces.value = result; message.value = spaces.value.length ? '找到可预约车位' : '当前时段暂无空位，请更换时间'
  })
}
async function reserve(space: Space) {
  await run(async () => {
    const criteria = queried.value
    if (!criteria || !spaces.value.includes(space)) { message.value = '查询条件已变化，请重新查询'; return }
    const body = { vehicleId: vehicleId.value, spaceId: space.id, startAt: businessInstant(criteria.startAt), endAt: businessInstant(criteria.endAt) }
    const order = await api<Reservation>('/reservations', body, requestKey('reserve', body)); keys.delete('reserve' + JSON.stringify(body)); message.value = '预约已创建，请在付款截止前完成模拟支付'
    bill.value = null; spaces.value = []; queried.value = null
    page.value = 'orders'; selectedBill.value = order.reservationId; await loadOrders()
  })
}
async function loadOrders() {
  orders.value = await api<Reservation[]>(page.value === 'property' ? '/property/reservations?communityId=' + communityId.value : '/reservations')
}
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
    await api('/reservations/' + id + '/cancel', {}); await loadOrders(); message.value = '预约已关闭；若已付款，退款将异步处理'
  })
}
async function gate(id: string, action: string) { await run(async () => { await api('/property/reservations/' + id + '/' + action, {}, requestKey(action, id)); await loadOrders(); message.value = action === 'entry' ? '入场成功' : '离场成功，费用将同步到账单' }) }
async function changePage(target: string) {
  page.value = target; message.value = ''
  await run(async () => { if (target === 'orders' || target === 'property') await loadOrders(); await refreshTime() })
}
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
onMounted(() => { clockTimer = setInterval(() => { clockTick.value = performance.now() }, 1000); timer = setInterval(() => {
  if (user.value && !busy.value && page.value === 'bill' && selectedBill.value) {
    void run(async () => { await refreshBill(selectedBill.value) })
  }
}, 5000) })
onUnmounted(() => { if (timer) clearInterval(timer); if (clockTimer) clearInterval(clockTimer) })
</script>

<template>
  <div class="parking-app">
    <header class="topbar"><div><span class="brand-mark">P</span><strong>悦享泊</strong><small>共享邻里车位，悦享便捷停车。</small></div><span class="demo-tag">本地演示 · 模拟支付</span></header>
    <main v-if="!user" class="login-layout">
      <section class="welcome"><p class="eyebrow">悦享泊——智慧社区车位共享与结算平台</p><h1>让闲置车位<br>连接每一次归来。</h1><p>查询共享时段，预约心仪车位。<br>从入场到结账，一笔账单清晰记录。</p><div class="steps">01 选位　 →　 02 预约　 →　 03 停车</div></section>
      <form class="panel login" @submit.prevent="login"><h2>欢迎登录</h2><p class="muted">使用本地演示账号，密码见私密配置文件。</p><label>账号<input v-model="username" autocomplete="username" required placeholder="driver / property / admin"></label><label>密码<input v-model="password" type="password" autocomplete="current-password" required></label><button class="primary" :disabled="busy">{{ busy ? '登录中…' : '登录' }}</button><p class="hint">driver 车主端 · property 物业端 · admin 演示管理</p><p v-if="message" role="alert" class="error">{{ message }}</p></form>
    </main>
    <template v-else>
      <div class="workspace"><aside><div class="profile"><b>{{ user.username }}</b><small>{{ role }}</small></div><nav>
        <button v-if="isDriver" :class="{ active: page === 'spaces' }" @click="changePage('spaces')" :disabled="busy">查询与预约</button>
        <button v-if="isDriver" :class="{ active: page === 'orders' || page === 'bill' }" @click="changePage('orders')" :disabled="busy">我的预约与账单</button>
        <button v-if="isProperty" :class="{ active: page === 'property' }" @click="changePage('property')" :disabled="busy">物业入离场</button>
        <button :class="{ active: page === 'ai' }" @click="changePage('ai')" :disabled="busy">停车助手 · 模型桩</button>
        <button v-if="role === 'PLATFORM'" :class="{ active: page === 'demo' }" @click="changePage('demo')" :disabled="busy">演示时间</button>
      </nav><button class="logout" @click="logout" :disabled="busy">退出登录</button></aside>
      <section class="content"><div class="context"><span>{{ communityName }}</span><span>演示时间 {{ date(now) }}</span></div>
        <p v-if="message" role="status" :class="['notice', { error }]">{{ message }}</p>
        <template v-if="page === 'spaces'">
          <h1>找到这一程的车位</h1><p class="muted">时间统一为北京时间（Asia/Shanghai）。提前至少15分钟预约，时间按15分钟对齐；单次30分钟至24小时。</p>
          <form class="panel filters" @submit.prevent="query">
            <label>社区<select v-model="communityId"><option v-for="c in communities" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
            <label>开始时间<input v-model="startAt" type="datetime-local" step="900" required></label><label>结束时间<input v-model="endAt" type="datetime-local" step="900" required></label>
            <label>本人车辆<select v-model="vehicleId" required><option v-for="v in vehicles" :key="v.id" :value="v.id">{{ v.plate }}</option></select></label>
            <button class="primary" :disabled="busy">查询车位</button>
          </form>
          <div class="space-grid"><article v-for="s in spaces" :key="s.id" class="panel space"><span class="available">可预约</span><h2>{{ s.name }}</h2><p class="muted">{{ communityName }} · 共享车位</p><div class="price">{{ money(s.hourlyFen) }}<small> / 小时</small></div><p>本次预计 {{ money(s.estimatedFen) }}</p><button class="primary" :disabled="busy || !vehicleId || !queried" @click="reserve(s)">预约此车位</button></article></div>
          <p v-if="!spaces.length" class="empty">选择社区和时间，查询当前可预约车位。</p>
        </template>
        <template v-if="page === 'orders' || page === 'property'">
          <div class="title-row"><h1>{{ page === 'property' ? '物业入离场工作台' : '我的预约' }}</h1><button @click="run(loadOrders)" :disabled="busy">刷新列表</button></div>
          <div class="panel table-wrap"><table><thead><tr><th>预约 / 车辆</th><th>时段</th><th>状态</th><th>预计金额</th><th>操作</th></tr></thead><tbody>
            <tr v-for="o in orders" :key="o.reservationId"><td><code>{{ o.reservationId }}</code><br>{{ o.plate }}</td><td>{{ date(o.startAt) }}<br>{{ date(o.endAt) }}</td><td><span class="status">{{ names[o.status] || o.status }}</span><small v-if="o.status === 'WAIT_PAY'">付款截止 {{ date(o.payDeadline) }}</small></td><td>{{ money(o.prepayFen) }}</td><td class="actions">
              <template v-if="page === 'orders'"><button @click="showBill(o.reservationId)" :disabled="busy">查看账单</button><button v-if="['WAIT_PAY', 'CONFIRMED'].includes(o.status)" @click="cancel(o.reservationId)" :disabled="busy || !canCancel(o)" :title="canCancel(o) ? '预约开始前可取消' : '预约已开始，不能自主取消'">取消预约</button></template>
              <template v-else><button v-if="o.status === 'CONFIRMED'" @click="gate(o.reservationId, 'entry')" :disabled="busy">办理入场</button><button v-if="o.status === 'IN_USE'" @click="gate(o.reservationId, 'exit')" :disabled="busy">办理离场</button></template>
            </td></tr></tbody></table><p v-if="!orders.length" class="empty">暂无预约记录</p></div>
        </template>
        <template v-if="page === 'bill'">
          <div class="title-row"><h1>停车账单</h1><button @click="showBill(selectedBill)" :disabled="busy">刷新账单</button></div>
          <p class="muted">预约号 {{ selectedBill }} · 金额以后台账单为准，每5秒刷新状态。</p>
          <section v-if="bill && currentBill" class="panel bill"><span class="status">{{ names[bill.status] }}</span><div class="amounts"><div><small>预约费用</small><b>{{ money(bill.prepayFen) }}</b></div><div><small>超时费用</small><b>{{ money(bill.overtimeFen) }}</b></div><div><small>已付合计</small><b>{{ money(Number(bill.paidPrepayFen) + Number(bill.paidOvertimeFen)) }}</b></div><div><small>已退金额</small><b>{{ money(bill.refundFen) }}</b></div></div>
            <p v-if="activePayment" class="notice">付款或退款正在确认；标记待人工核查时请联系管理员，不要重复付款。</p>
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
