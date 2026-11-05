import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync,rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WorkerGoals,TASK_BUDGET,chargeElapsed } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-goals.js'
import { prepareDelegatedWorker } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-delegation.js'
import { WorkerQueue } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/workers.js'

function fixture(t) {
  const directory=mkdtempSync(join(tmpdir(),'whale-goals-'))
  t.after(()=>rmSync(directory,{recursive:true,force:true}))
  const queue=new WorkerQueue(directory),goals=new WorkerGoals(directory,queue.rows)
  return {directory,queue,goals}
}
test('worker completion requires separate criterion verification; new evidence invalidates the old acceptance',t=>{
  const {directory,queue,goals}=fixture(t)
  goals.define('交接','parent',{outcome:'核对交接日期',criteria:['两份资料日期一致']})
  queue.save({id:'a',group:'交接',owner:'parent',sessionId:'a',state:'completed',revision:1,summary:'6 月 8 日'})
  queue.save({id:'b',group:'交接',owner:'parent',sessionId:'b',state:'waiting',revision:1,summary:'日期尚不确定'})
  assert.equal(goals.view('交接').state,'working')
  assert.throws(()=>goals.verify('交接','parent',goals.view('交接').fingerprint,[]),/先完成/)
  queue.rows.get('b').state='completed'
  const ready=goals.view('交接')
  assert.equal(ready.state,'verification')
  assert.throws(()=>goals.verify('交接','other',ready.fingerprint,[]),/协调会话/)
  goals.verify('交接','parent',ready.fingerprint,[{criterion:'两份资料日期一致',taskId:'b',passed:false,evidence:'第二份资料无明确日期'}])
  assert.equal(goals.view('交接').state,'verification')
  queue.rows.get('b').summary='6 月 8 日'
  assert.throws(()=>goals.verify('交接','parent',ready.fingerprint,[]),/已变化/)
  const checked=goals.view('交接')
  goals.verify('交接','parent',checked.fingerprint,[{criterion:'两份资料日期一致',taskId:'b',passed:true,evidence:'重新阅读 a、b 原文，均为 6 月 8 日'}])
  const restored=new WorkerGoals(directory,queue.rows)
  assert.equal(restored.view('交接').state,'verified')
  queue.rows.get('a').revision++
  assert.equal(restored.view('交接').state,'verification')
  queue.rows.get('a').authorizationRevoked=true
  assert.equal(restored.view('交接').state,'working')
})
test('task and goal usage survive restart; no new budget is granted by resuming a task',t=>{
  const {directory,queue,goals}=fixture(t)
  goals.define('整理','parent',{outcome:'整理两份文件',criteria:['内容均已核对'],budget:{tokens:1200,calls:3,milliseconds:60000}})
  for(const id of ['a','b']) queue.save({id,owner:'parent',sessionId:id,group:'整理',mode:'read',state:'running',revision:1,
    budget:{...TASK_BUDGET},budgetUsage:{tokens:700,calls:1,milliseconds:800},budgetStartedAt:Date.now()-100})
  assert.match(goals.denial(queue.rows.get('a')),/目标的总预算/)
  const row=queue.rows.get('a'); chargeElapsed(row); queue.save(row)
  assert.ok(row.budgetUsage.milliseconds>=900)
  const recovered=new WorkerQueue(directory),ledger=new WorkerGoals(directory,recovered.rows)
  assert.equal(recovered.rows.get('a').state,'queued')
  assert.match(ledger.denial(recovered.rows.get('a')),/总预算/)
  recovered.rows.get('a').budgetUsage.calls=100
  assert.match(ledger.denial(recovered.rows.get('a')),/本工单预算/)
})
test('permissions are captured before child creation waits and installed before publication, including custom and Auto',async()=>{
  for(const initial of ['read-only','workspace-write','auto']) {
    let mode=initial==='auto'?'workspace-write':initial,published=false
    const log=[],parent={id:'parent',session:{},ctx:{}},child={session:{append:(type,data)=>{assert.equal(published,false);log.push({type,data})}},ctx:{}}
    const api={captureDelegatedPolicyOverrides:()=>({sandboxMode:mode,approvalPolicy:'never',permissionPreset:initial==='auto'?'auto':undefined}),
      appendDelegatedPolicyOverrides:(session,policy)=>{session.append('sandbox/mode',{mode:policy.sandboxMode,source:'delegation'});session.append('approval/policy',{policy:policy.approvalPolicy,source:'delegation'})}}
    const ctx={agents:{get:()=>undefined,resume:()=>assert.fail('unexpected resume'),
      create:async request=>{await request.setup(child.ctx,child);published=true;return {agent:child}}},
      sessionPersistence:{stat:async()=>{mode='danger-full-access';return undefined},flush:async()=>{}}}
    const result=await prepareDelegatedWorker(ctx,parent,{sessionId:'child',mode:'read',route:{}},api)
    assert.equal(log[0].data.mode,initial==='auto'?'workspace-write':initial)
    assert.equal(log[1].data.policy,'never'); assert.equal(published,true)
    if(initial==='auto') assert.equal(result.captured.permissionPreset,'auto')
  }
})
test('an unavailable safe factory never publishes a default-permission child',async()=>{
  await assert.rejects(prepareDelegatedWorker({}, {},{},{}),/缺少安全委派接口/)
})

test('a persisted delegated worker resumes under its original parent before inbox delivery',async()=>{
  const parent={ctx:{}}, child={ctx:{},session:{}}, events=[]
  child.ctx.get=name=>name==='agentPresets'?{composeFrom:(childScope,parentScope)=>{
    assert.equal(childScope,child.ctx)
    assert.equal(parentScope,parent.ctx)
    events.push('inherited-tools')
  }}:undefined
  const row={mode:'read',sessionId:'persisted-worker',route:{}}
  const api={captureDelegatedPolicyOverrides:()=>({sandboxMode:'read-only'}),
    appendDelegatedPolicyOverrides:(_,policy)=>events.push(policy.sandboxMode)}
  const ctx={agents:{get:()=>undefined,create:()=>assert.fail('duplicate creation'),
    resume:async request=>{
      assert.equal(request.resumeSessionId,row.sessionId)
      assert.equal(request.parentAgent,parent)
      await request.setup(child.ctx,child)
      events.push('published')
      return {agent:child}
    }},sessionPersistence:{stat:async id=>({id}),flush:async()=>events.push('flushed')}}
  assert.equal((await prepareDelegatedWorker(ctx,parent,row,api)).agent,child)
  assert.deepEqual(events,['read-only','inherited-tools','published','flushed'])
  ctx.sessionPersistence.stat=async()=>{throw new Error('storage unavailable')}
  await assert.rejects(prepareDelegatedWorker(ctx,parent,row,api),/storage unavailable/)
})
