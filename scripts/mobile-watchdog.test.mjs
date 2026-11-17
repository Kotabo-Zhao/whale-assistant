import test from 'node:test'
import assert from 'node:assert/strict'
import { beginWorkerHealth, recordWorkerHealth, acceptsWatchdogHold, assessWorkerStall, takeStallHold, STALL_QUIET_MS } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-health.js'

test('identical results accumulate evidence, changed results clear repetition, and raw content is discarded',()=>{
  const row={state:'running',revision:1,input:'Verify the total'}
  beginWorkerHealth(row,100)
  for(let i=0;i<5;i++) {
    recordWorkerHealth(row,{type:'tool/call',data:{callId:String(i),name:'read',args:{path:'private-note'}}},200+i)
    recordWorkerHealth(row,{type:'tool/result',data:{message:{toolCallId:String(i),content:[{type:'text',text:'secret result'}]}}},210+i)
  }
  assert.equal(row.health.repeated,5)
  assert.equal(row.health.recent.length,5)
  assert.doesNotMatch(JSON.stringify(row.health),/private-note|secret result/)
  recordWorkerHealth(row,{type:'tool/call',data:{callId:'new',name:'read',args:{path:'new-note'}}},300)
  recordWorkerHealth(row,{type:'tool/result',data:{message:{toolCallId:'new',content:['different result']}}},310)
  assert.equal(row.health.repeated,1)
  assert.equal(row.health.lastProgressAt,310)
})

test('a watchdog hold cannot cancel new progress, a completed report, or a resumed worker',()=>{
  const row={state:'running',revision:1}
  beginWorkerHealth(row,100)
  const command={revision:1,runId:row.health.runId,sequence:0,heartbeatAt:100,reason:'stalled',explanation:'Repeated identical results'}
  assert.equal(acceptsWatchdogHold(row,command),true)
  row.health.sequence++
  assert.equal(acceptsWatchdogHold(row,command),false)
  row.health.sequence=0;row.reportState='completed'
  assert.equal(acceptsWatchdogHold(row,command),false)
  row.reportState=null;row.authorizationRevoked=true
  assert.equal(acceptsWatchdogHold(row,command),false)
  row.authorizationRevoked=false
  const heartbeat={...command,reason:'unresponsive'}
  row.health.heartbeatAt=200
  assert.equal(acceptsWatchdogHold(row,heartbeat),false)
  beginWorkerHealth(row,300)
  assert.equal(acceptsWatchdogHold(row,command),false)
})

test('evidence and pending calls stay bounded and provider backoff is visible to the independent monitor',()=>{
  const row={state:'running'};beginWorkerHealth(row,100)
  for(let i=0;i<40;i++) {
    recordWorkerHealth(row,{type:'tool/call',data:{callId:String(i),name:'read'}},100+i)
    recordWorkerHealth(row,{type:'assistant/message',data:{message:{content:[{type:'text',text:String(i)}]}}},100+i)
  }
  assert.equal(Object.keys(row.health.pending).length,16)
  assert.equal(row.health.recent.length,8)
  recordWorkerHealth(row,{type:'llm/retry',data:{delayMs:30000}},200)
  assert.equal(row.health.retryUntil,30200)
})

function repeatSameResult(row, count, at) {
  for (let i = 0; i < count; i++) {
    recordWorkerHealth(row, { type: 'tool/call', data: { callId: `c${i}`, name: 'read', args: { path: 'note' } } }, at)
    recordWorkerHealth(row, { type: 'tool/result', data: { message: { toolCallId: `c${i}`, content: 'same' } } }, at + 1)
  }
}

test('identical results are not a stall until they stay quiet, and a hold is issued once', () => {
  const row = { state: 'running', revision: 1 }
  beginWorkerHealth(row, 0)
  repeatSameResult(row, 3, 10)
  assert.equal(assessWorkerStall(row, 10 + STALL_QUIET_MS - 1), null)
  const due = 11 + STALL_QUIET_MS
  const stall = assessWorkerStall(row, due)
  assert.equal(stall.reason, 'stalled')
  assert.equal(takeStallHold(row, due)?.runId, row.health.runId)
  assert.equal(takeStallHold(row, due), null)
})

test('in-flight work, provider retry, and a new result are not stalls', () => {
  const row = { state: 'running', revision: 1 }
  beginWorkerHealth(row, 0)
  repeatSameResult(row, 3, 10)
  const due = 11 + STALL_QUIET_MS
  row.health.pending.busy = { name: 'read' }
  assert.equal(assessWorkerStall(row, due), null)
  delete row.health.pending.busy
  row.health.retryUntil = due + 1000
  assert.equal(assessWorkerStall(row, due), null)
  row.health.retryUntil = due - 1
  recordWorkerHealth(row, { type: 'tool/call', data: { callId: 'fresh', name: 'read', args: { path: 'other' } } }, due)
  recordWorkerHealth(row, { type: 'tool/result', data: { message: { toolCallId: 'fresh', content: 'different' } } }, due)
  assert.equal(row.health.repeated, 1)
  assert.equal(assessWorkerStall(row, due), null)
})

test('new user information invalidates an outstanding assessment before and after it reaches the worker',()=>{
  const row={state:'running',revision:1,inputs:[]};beginWorkerHealth(row,100)
  const command={revision:1,runId:row.health.runId,sequence:0,reason:'stalled',explanation:'No progress'}
  row.inputs.push({id:'update-1',kind:'update',text:'Use the corrected source'})
  assert.equal(acceptsWatchdogHold(row,command),false)
  row.inputs[0].readAt=200
  recordWorkerHealth(row,{type:'user/message',data:{id:'update-1'}},200)
  assert.equal(acceptsWatchdogHold(row,command),false)
  assert.match(row.health.task,/corrected source/)
})
