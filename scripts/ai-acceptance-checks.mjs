import assert from 'node:assert/strict'
import {setTimeout as delay} from 'node:timers/promises'

export function checkBoundary(c,answer){
 assert.equal(answer.status,c.expectedAction)
 assert.deepEqual([...(answer.missingFields??[])].sort(),[...c.expectedMissing].sort())
 assert.equal((answer.cards??[]).length,0)
 assert.ok(!answer.bill)
}

export function checkBillFacts(expected,bill){
 for(const [key,value] of Object.entries(expected))assert.equal(bill[key],value,key)
}

export async function waitForExplanation(history,logs,key,timeoutMs=6000){
 const deadline=Date.now()+timeoutMs
 while(Date.now()<deadline){
  const message=(await history()).items.find(m=>m.requestKey===key)
  if(message){
   const call=(await logs()).items.find(c=>c.messageId===message.requestId && c.callPurpose==='BILL_EXPLANATION'
     && c.status==='RESERVED' && Date.parse(c.leaseUntil)>Date.now())
   if(call)return {messageId:message.requestId,callId:call.requestId,admittedAt:call.createdAt}
   if(message.status!=='PROCESSING')break
  }
  await delay(50)
 }
 throw new Error('No active bill explanation for this message')
}
