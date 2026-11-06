import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WorkerCoordinator } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-coordinator.js'
import { WorkerGoals } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-goals.js'
import { foregroundWaitDenial } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/foreground-policy.js'

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(),'whale-coordinator-')); t.after(() => rmSync(directory,{recursive:true,force:true}))
  const rows = new Map(), goals = new WorkerGoals(directory,rows), agent = { status:'idle',inbox:{nextTurn:[],nextStep:[]} }
  let now = 1000
  const delivered = [], resolve = async () => agent, deliver = async (...args) => delivered.push(args)
  let coordinator = new WorkerCoordinator(directory,rows,goals,() => now)
  return { rows, goals, agent, delivered, get coordinator() { return coordinator },
    advance: ms => { now+=ms }, pump: () => coordinator.pump(resolve,deliver),
    restart: () => { coordinator = new WorkerCoordinator(directory,rows,goals,() => now) },
    add: (id, state='running') => rows.set(id,{id,owner:'session-user',revision:1,title:id,state,summary:''}),
    damage: () => writeFileSync(coordinator.path,'{damaged'),
  }
}
test('合并多个 Worker 的变化，前台繁忙及有人类输入时不插队', async t => {
  const f=fixture(t); f.add('a'); f.add('b'); await f.pump(); f.advance(31000)
  f.agent.status='running'; await f.pump(); assert.equal(f.delivered.length,0)
  f.agent.status='idle'; f.agent.inbox.nextTurn.push({id:'human'}); await f.pump(); assert.equal(f.delivered.length,0)
  f.agent.inbox.nextTurn=[]; await f.pump(); assert.equal(f.delivered.length,1)
  assert.match(f.delivered[0][2], /"a","b"/)
  const state=f.coordinator.status('session-user')
  f.coordinator.report('session-user',state.fingerprint,'两项正在执行','等待回执，不轮询')
  f.advance(60000); await f.pump(); assert.equal(f.delivered.length,1)
  f.restart(); await f.pump(); assert.equal(f.delivered.length,1)
})
test('新回执优先合并，旧指纹不能确认新状态，汇报不冒充验收', async t => {
  const f=fixture(t); f.add('a'); await f.pump(); f.advance(31000); await f.pump()
  const old=f.coordinator.status('session-user').fingerprint
  f.rows.get('a').state='completed'; f.rows.get('a').summary='读取完成'
  assert.throws(() => f.coordinator.report('session-user',old,'完成',''),/状态已变化/)
  f.goals.ensure('target','session-user'); f.rows.get('a').group='target'
  f.goals.define('target','session-user',{outcome:'交付核验报告',criteria:['证据齐全']})
  const state=f.coordinator.status('session-user')
  f.coordinator.report('session-user',state.fingerprint,'工单已完成，尚待核验','核对原文')
  assert.equal(f.goals.view('target').state,'verification')
  assert.equal(f.coordinator.status('session-another').tasks.length,0)
})
test('投递成功不等于处理成功，重启后只有限重试，权限恢复记录不自动执行', async t => {
  const f=fixture(t); f.add('a','blocked'); await f.pump(); f.advance(4000); await f.pump()
  assert.equal(f.delivered.length,1)
  const id=f.coordinator.status('session-user').coordination.pending.messageId
  f.coordinator.consumed('session-user',id)
  assert.notEqual(f.coordinator.status('session-user').coordination.acknowledged,f.coordinator.status('session-user').fingerprint)
  f.restart(); f.advance(120001); await f.pump(); assert.equal(f.delivered.length,2)
  f.advance(120001); await f.pump(); assert.equal(f.delivered.length,2)
  f.damage(); f.restart(); f.rows.get('a').summary='changed'; await f.pump()
  assert.equal(f.coordinator.status('session-user').recoveryRequired,true)
  f.restart(); assert.equal(f.coordinator.status('session-user').recoveryRequired,true)
  assert.equal(f.delivered.length,2)
})
test('前台等待守卫拒绝长轮询，短查询和后台启动可用', () => {
  assert.ok(foregroundWaitDenial({name:'mobile_device_wait',arguments:{milliseconds:1000}}))
  assert.ok(foregroundWaitDenial({name:'bash',arguments:{command:'sleep 3',timeoutMs:1000}}))
  assert.ok(foregroundWaitDenial({name:'job_output',arguments:{wait:true,timeout_ms:6000}}))
  assert.equal(foregroundWaitDenial({name:'job_output',arguments:{wait:true,timeout_ms:5000}}),undefined)
  assert.equal(foregroundWaitDenial({name:'bash',arguments:{command:'rg needle path',timeoutMs:5000}}),undefined)
  assert.equal(foregroundWaitDenial({name:'bash',arguments:{command:'long-job',run_in_background:true}}),undefined)
})
test('同一阻塞不因其他任务进度重复推送，未送达的汇报不会被内部进度覆盖', async t => {
  const f=fixture(t); f.add('a','blocked'); f.add('b')
  let state=f.coordinator.status('session-user')
  f.coordinator.report('session-user',state.fingerprint,'a 需要信息','等待回答',true)
  await f.coordinator.flushReports(async () => { throw Error('bridge offline') })
  f.rows.get('b').progress='已读到下一页'
  state=f.coordinator.status('session-user')
  f.coordinator.report('session-user',state.fingerprint,'a 仍需信息，b 有进度','等待回答',true)
  assert.equal(f.coordinator.status('session-user').coordination.report.delivery,'internal')
  f.restart()
  const published=[]; await f.coordinator.flushReports(async report => published.push(report))
  assert.equal(published.length,1); assert.equal(published[0].summary,'a 需要信息')
  await f.coordinator.flushReports(async report => published.push(report)); assert.equal(published.length,1)
  f.rows.get('b').state='completed'
  state=f.coordinator.status('session-user')
  f.coordinator.report('session-user',state.fingerprint,'b 完成，a 等待','核对 b',true)
  await f.coordinator.flushReports(async report => published.push(report)); assert.equal(published.length,2)
})
