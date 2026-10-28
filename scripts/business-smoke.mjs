import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { performance } from 'node:perf_hooks'
const base = 'http://127.0.0.1:9090/api/v1'
const results = []
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function request(path, token, body, key) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  const response = await fetch(base + path, { method: body === undefined ? 'GET' : 'POST', headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(26000) })
  const json = await response.json()
  return { status: response.status, ...json }
}
async function ok(path, token, body, key) {
  const r = await request(path, token, body, key)
  assert.equal(r.code, 'OK', path + ': ' + r.status + ' ' + r.code)
  assert.match(r.traceId, /^[a-f0-9-]{36}$/)
  return r.data
}
async function login(name) {
  return (await ok('/auth/login', null, { username: name, password: process.env['DEMO_' + name.toUpperCase() + '_PASSWORD'] })).accessToken
}
async function check(name, fn) {
  const start = performance.now()
  try { await fn(); results.push({ name, status: 'PASS', durationMs: Math.round(performance.now() - start) }); console.log('PASS ' + name) }
  catch (e) { results.push({ name, status: 'FAIL', error: e.message }); throw e }
}
async function until(fn, predicate, label) {
  const deadline = Date.now() + 25000
  while (Date.now() < deadline) { const value = await fn(); if (predicate(value)) return value; await sleep(500) }
  throw new Error('Timeout: ' + label)
}
const key = () => crypto.randomUUID()
const iso = ms => new Date(ms).toISOString()
const nextSlot = now => Math.ceil((new Date(now).getTime() + 1800000) / 900000) * 900000
let driver, driver2, property, admin, winner, loser, reservation, bill, start, end
try {
  await check('login and current authorization', async () => {
    ;[driver, driver2, property, admin] = await Promise.all(['driver', 'driver2', 'property', 'admin'].map(login))
    assert.equal((await ok('/auth/me', driver)).id, '1')
    assert.equal((await request('/vehicles')).status, 401)
    assert.equal((await ok('/vehicles', driver))[0].id, '40')
    assert.equal((await request('/property/reservations', driver)).status, 403)
    assert.equal((await request('/property/reservations?communityId=999', property)).status, 403)
    assert.equal((await fetch('http://127.0.0.1:9090/internal/v1/auth/state')).status, 404)
  })
  await check('available query, alignment and vehicle ownership', async () => {
    start = nextSlot((await ok('/demo/clock', driver)).now); end = start + 3600000
    const query = '/spaces/available?communityId=10&startAt=' + iso(start) + '&endAt=' + iso(end)
    const spaces = await ok(query)
    assert.equal(spaces[0].id, '20'); assert.equal(spaces[0].estimatedFen, 500)
    assert.equal((await request('/spaces/available?communityId=10&startAt=' + iso(start + 1000) + '&endAt=' + iso(end))).status, 422)
    assert.equal((await request('/reservations', driver, { vehicleId: '41', spaceId: '20', startAt: iso(start), endAt: iso(end) }, key())).code, 'VEHICLE_FORBIDDEN')
  })
  await check('concurrent reservation and idempotent retry', async () => {
    const keys = [key(), key()]
    const bodies = ['40', '41'].map(vehicleId => ({ vehicleId, spaceId: '20', startAt: iso(start), endAt: iso(end) }))
    const tokens = [driver, driver2]
    const replies = await Promise.all(tokens.map((token, i) => request('/reservations', token, bodies[i], keys[i])))
    assert.deepEqual(replies.map(r => r.status).sort(), [200, 409])
    const index = replies.findIndex(r => r.status === 200)
    winner = tokens[index]; loser = tokens[1-index]; reservation = replies[index].data
    assert.equal((await ok('/reservations', winner, bodies[index], keys[index])).reservationId, reservation.reservationId)
    assert.equal((await request('/reservations', winner, { ...bodies[index], endAt: iso(end + 900000) }, keys[index])).code, 'IDEMPOTENCY_CONFLICT')
    assert.equal((await request('/property/reservations/' + reservation.reservationId + '/entry', property, {}, key())).code, 'ENTRY_NOT_ALLOWED')
  })
  await check('durable payment, duplicate key, owner-only bill', async () => {
    await until(() => request('/bills/' + reservation.reservationId, winner), r => r.code === 'OK', 'bill creation')
    const paymentKey = key(), body = { reservationId: reservation.reservationId, kind: 'PREPAY' }
    const payments = await Promise.all([ok('/payments', winner, body, paymentKey), ok('/payments', winner, body, paymentKey)])
    assert.equal(payments[0].id, payments[1].id)
    await until(() => ok('/reservations/' + reservation.reservationId, winner), r => r.status === 'CONFIRMED', 'confirmation')
    bill = await ok('/bills/' + reservation.reservationId, winner)
    assert.equal(bill.paidPrepayFen, 500); assert.equal(bill.attempts.length, 1)
    assert.equal((await request('/bills/' + reservation.reservationId, loser)).status, 404)
    assert.equal((await request('/payments/' + payments[0].id, loser)).status, 404)
  })
  await check('property entry and exit with overtime', async () => {
    await ok('/demo/clock', admin, { now: iso(start) })
    const entryKey = key()
    assert.equal((await ok('/property/reservations/' + reservation.reservationId + '/entry', property, {}, entryKey)).status, 'IN_USE')
    assert.equal((await ok('/property/reservations/' + reservation.reservationId + '/entry', property, {}, entryKey)).status, 'IN_USE')
    await until(() => ok('/bills/' + reservation.reservationId, winner), b => b.status === 'IN_USE', 'entry reflected on bill')
    await ok('/demo/clock', admin, { now: iso(end + 1000) })
    const exited = await ok('/property/reservations/' + reservation.reservationId + '/exit', property, {}, key())
    assert.equal(exited.overtimeFen, 250)
    await until(() => ok('/bills/' + reservation.reservationId, winner), b => b.status === 'COMPLETED' && b.overtimeFen === 250, 'completed bill')
    await ok('/payments', winner, { reservationId: reservation.reservationId, kind: 'OVERTIME' }, key())
    await until(() => ok('/bills/' + reservation.reservationId, winner), b => b.paidOvertimeFen === 250, 'overtime payment')
  })
  await check('paid cancellation refunds once', async () => {
    const s = nextSlot((await ok('/demo/clock', driver)).now)
    const order = await ok('/reservations', driver, { vehicleId: '40', spaceId: '20', startAt: iso(s), endAt: iso(s + 1800000) }, key())
    await until(() => request('/bills/' + order.reservationId, driver), r => r.code === 'OK', 'refund bill')
    await ok('/payments', driver, { reservationId: order.reservationId, kind: 'PREPAY' }, key())
    await until(() => ok('/reservations/' + order.reservationId, driver), o => o.status === 'CONFIRMED', 'refund confirmation')
    await ok('/reservations/' + order.reservationId + '/cancel', driver, {})
    await ok('/reservations/' + order.reservationId + '/cancel', driver, {})
    const refunded = await until(() => ok('/bills/' + order.reservationId, driver), b => b.refundFen === 250, 'automatic refund')
    assert.equal(refunded.attempts.filter(p => p.kind === 'REFUND').length, 1)
  })
  await check('unpaid expiry cannot be paid or revived', async () => {
    const n = (await ok('/demo/clock', driver)).now, s = nextSlot(n)
    const order = await ok('/reservations', driver, { vehicleId: '40', spaceId: '20', startAt: iso(s), endAt: iso(s + 1800000) }, key())
    await ok('/demo/clock', admin, { now: iso(new Date(n).getTime() + 601000) })
    await until(() => ok('/reservations/' + order.reservationId, driver), o => o.status === 'EXPIRED', 'expiry')
    await until(() => request('/bills/' + order.reservationId, driver), b => b.data?.status === 'EXPIRED', 'closed bill')
    assert.equal((await request('/payments', driver, { reservationId: order.reservationId, kind: 'PREPAY' }, key())).code, 'ORDER_CLOSED')
  })
  await check('blocked arrival is retained and refunded as space unavailable', async () => {
    const n = (await ok('/demo/clock', driver)).now, s = nextSlot(n), boundary = s + 3600000
    const first = await ok('/reservations', driver, { vehicleId: '40', spaceId: '20', startAt: iso(s), endAt: iso(boundary) }, key())
    const second = await ok('/reservations', driver2, { vehicleId: '41', spaceId: '20', startAt: iso(boundary), endAt: iso(boundary+1800000) }, key())
    for(const [order, token] of [[first,driver],[second,driver2]]){
      await until(() => request('/bills/' + order.reservationId, token), r => r.code === 'OK', 'adjacent bill')
      await ok('/payments', token, { reservationId: order.reservationId, kind:'PREPAY' }, key())
      await until(() => ok('/reservations/' + order.reservationId, token), o => o.status === 'CONFIRMED', 'adjacent confirmation')
    }
    await ok('/demo/clock', admin, { now:iso(s) })
    await ok('/property/reservations/' + first.reservationId + '/entry', property, {}, key())
    await ok('/demo/clock', admin, { now:iso(boundary) })
    assert.equal((await request('/property/reservations/' + second.reservationId + '/entry', property, {}, key())).code, 'SPACE_OCCUPIED')
    assert((await ok('/reservations/' + second.reservationId, driver2)).entryBlockedAt)
    await ok('/demo/clock', admin, { now:iso(boundary+901000) })
    const closed = await until(() => ok('/reservations/' + second.reservationId, driver2), o => o.status === 'SPACE_UNAVAILABLE', 'blocked closure')
    assert.equal(closed.closeReason, 'SPACE_UNAVAILABLE')
    await until(() => ok('/bills/' + second.reservationId, driver2), b => b.refundFen === 250, 'blocked refund')
    await ok('/property/reservations/' + first.reservationId + '/exit', property, {}, key())
    await until(() => ok('/bills/' + first.reservationId, driver), b => b.overtimeFen === 250, 'blocked test exit bill')
    await ok('/payments', driver, { reservationId:first.reservationId,kind:'OVERTIME' },key())
  })
  await check('AI stub normal/failure and 30-second delay isolation', async () => {
    const stub = await ok('/ai/prototype', driver, { question: '如何停车' })
    assert.equal(stub.mode, 'STUB'); assert.equal(stub.realModelAccepted, false)
    assert.equal((await request('/ai/prototype', driver, { question: 'test', scenario: 'slow' })).status, 403)
    const failures = await Promise.all(Array.from({ length: 5 }, () => request('/ai/prototype', admin, { question: 'test', scenario: 'fail' })))
    assert(failures.every(r => r.status === 503))
    const began = performance.now()
    const delayed = Promise.all(Array.from({ length: 10 }, () => request('/ai/prototype', admin, { question: 'test', scenario: 'slow' })))
    await until(() => ok('/ai/status', admin), r => r.active === 10, 'ten isolated AI workers')
    const latency = []
    for (let i = 0; i < 8; i++) { const t = performance.now(); await ok('/vehicles', driver); latency.push(Math.round(performance.now()-t)) }
    const fresh = await login('driver2'); assert.equal((await ok('/auth/me', fresh)).id, '2')
    const result = await delayed
    assert(result.every(r => r.status === 504))
    const elapsed = performance.now() - began
    assert(elapsed >= 19000 && elapsed < 25000)
    assert(Math.max(...latency) < 2000, 'ordinary request blocked by AI')
    await until(() => ok('/ai/status', admin), r => r.active === 0 && r.queued === 0, 'AI worker cancellation')
    results.push({ name: 'AI isolation timings', status: 'PASS', ordinaryMaxMs: Math.max(...latency), timeoutMs: Math.round(elapsed), mode: 'STUB', concurrentSlowRequests: 10, consecutiveFailures: 5 })
  })
  await check('logout revokes token across services', async () => {
    await ok('/auth/logout', driver, {})
    assert.equal((await request('/auth/me', driver)).status, 401)
    assert.equal((await request('/vehicles', driver)).status, 401)
  })
} finally {
  const directory = process.env.BUSINESS_REPORT_DIR || '.runtime/business/verification'
  await mkdir(directory, { recursive: true })
  await writeFile(directory + '/api-results.json', JSON.stringify({ at: new Date().toISOString(), realAiAccepted: false, reservationId: reservation?.reservationId, results }, null, 2))
}
