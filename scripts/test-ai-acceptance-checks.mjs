import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {test} from 'node:test'
import {checkBoundary,checkBillFacts,waitForExplanation} from './ai-acceptance-checks.mjs'
const load=name=>JSON.parse(readFileSync(new URL('../docs/acceptance/'+name,import.meta.url),'utf8'))

test('all 20 boundaries require the specified action and missing fields',()=>{
 const cases=load('ai-m4-cases.json').filter(c=>c.category==='boundary')
 assert.equal(cases.length,20)
 for(const c of cases){
  const answer={status:c.expectedAction,missingFields:c.expectedMissing}
  checkBoundary(c,answer)
  assert.throws(()=>checkBoundary(c,{...answer,status:c.expectedAction==='CLARIFY'?'REFUSED':'CLARIFY'}))
  assert.throws(()=>checkBoundary(c,{...answer,missingFields:['wrong']}))
 }
})

test('an incorrect refund expectation fails against unchanged input',()=>{
 const name='部分退款含在途',input=load('ai-bill-inputs.json')[name]
 const expected=load('ai-bill-oracles.json').scenarios.find(o=>o.name===name).facts
 const paid=input.receipts.prepay+input.receipts.overtime
 const facts={prepayFen:input.prepay,overtimeFen:input.overtime,paidFen:paid,
  refundedFen:input.refunds.succeeded,pendingRefundFen:input.refunds.unknown,netPaidFen:paid-input.refunds.succeeded}
 checkBillFacts(expected,facts)
 assert.throws(()=>checkBillFacts({...expected,netPaidFen:200},facts),/netPaidFen/)
 assert.deepEqual(input,load('ai-bill-inputs.json')[name])
})

test('other messages, intent calls, and released reservations cannot trigger payment',async()=>{
 const history=async()=>({items:[{requestKey:'key',requestId:'target',status:'PROCESSING'}]})
 const active={messageId:'target',requestId:'correct',callPurpose:'BILL_EXPLANATION',status:'RESERVED',leaseUntil:new Date(Date.now()+30000).toISOString()}
 const noise=[{...active,messageId:'other'},{...active,callPurpose:'INTENT'},{...active,leaseUntil:null}]
 let polls=0
 const found=await waitForExplanation(history,async()=>({items:++polls===1?noise:[...noise,active]}),'key')
 assert.equal(polls,2)
 assert.equal(found.callId,'correct')
})
