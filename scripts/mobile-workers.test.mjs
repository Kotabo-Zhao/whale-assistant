import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WorkerQueue, installWorkers } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/workers.js'

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'mobile-workers-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  return { queue: new WorkerQueue(dir), dir }
}
test('unlimited reads run while device tasks serialize', t => {
  const { queue } = fixture(t)
  for (const [id, mode] of [['a', 'device'], ['b', 'device'], ['c', 'read'], ['d', 'read']])
    queue.save({ id, mode, state: 'queued', owner: 'parent', sessionId: id })
  assert.deepEqual(queue.runnable().map(row => row.id), ['a', 'c', 'd'])
  queue.save({ ...queue.rows.get('a'), state: 'running' })
  assert.deepEqual(queue.runnable().map(row => row.id), ['c', 'd'])
  queue.save({ ...queue.rows.get('c'), state: 'running' })
  assert.deepEqual(queue.runnable().map(row => row.id), ['d'])
  queue.save({ ...queue.rows.get('a'), state: 'waiting' })
  assert.deepEqual(queue.runnable().map(row => row.id), ['b', 'd'])
})
test('restart preserves queued and waiting tasks but never replays running side effects', t => {
  const { queue, dir } = fixture(t)
  for (const state of ['queued', 'running', 'waiting', 'completed']) queue.save({ id: state, sessionId: state, state, owner: 'one' })
  const sent = queue.rows.get('running')
  sent.inputs = [{ id: 'input-one', revision: 1, kind: 'request', text: 'work', delivery: 'sending' }]
  queue.save(sent)
  const restored = new WorkerQueue(dir)
  assert.equal(restored.rows.get('running').inputs[0].delivery, 'unknown')
  assert.equal(restored.rows.get('running').inputs[0].readAt, undefined)
  assert.equal(restored.rows.get('running').reports.length, 0)
  assert.equal(restored.rows.get('running').state, 'interrupted')
  assert.equal(restored.rows.get('waiting').state, 'waiting')
  assert.deepEqual(restored.runnable().map(row => row.id), ['queued'])
  assert.throws(() => restored.owned('waiting', 'other'), /不属于/)
})

test('restart resumes read tasks with a new receipt, but does not replay phone actions', t => {
  const { queue, dir } = fixture(t)
  for (const mode of ['read', 'device']) queue.save({ id: mode, mode, state: 'running', owner: 'parent', sessionId: mode,
    revision: 1, input: '检查资料', createdAt: Date.now(), inputs: [{ id: `mobile-worker:${mode}:input:1`, revision: 1, kind: 'request', text: '检查资料', delivery: 'sending' }] })
  const restored = new WorkerQueue(dir)
  const read = restored.rows.get('read'), device = restored.rows.get('device')
  assert.equal(read.state, 'queued'); assert.equal(read.revision, 2)
  assert.equal(read.inputs[0].delivery, 'unknown'); assert.equal(read.inputs[1].kind, 'recovery')
  assert.equal(read.inputs[1].recoveryOf, 1)
  assert.equal(device.state, 'interrupted'); assert.equal(device.inputs.length, 1)
})

test('worker question releases its slot and reply returns to that same worker and parent', async t => {
  const { dir } = fixture(t)
  const tools = new Map(), events = new Map(), agents = new Map()
  const makeAgent = id => {
    const log = [], guards = []
    const agent = { id, options: { provider: 'test', model: 'test' },
      session: { id, requestHeader: () => ({ config: { provider: 'test', model: 'test' } }), snapshotEvents: () => log },
      ctx: { tools: { guard: fn => guards.push(fn) }, systemPrompt: { section() {} } },
      inbox: { nextTurn: [], nextStep: [] }, followup: message => { agent.inbox.nextTurn.push(message) },
      cancel: () => {}, steer: message => { agent.lastSteer = message }, guards }
    agents.set(id, agent); return agent
  }
  const parent = makeAgent('session-parent')
  let failParentFlush = false
  const ctx = { on: (name, fn) => { events.set(name, fn) },
    tools: { register: tool => tools.set(tool.name, tool), guard() {} }, systemPrompt: { section() {} },
    sessionController: { resolveAgent: async id => agents.has(id) ? { agent: agents.get(id) } : { error: { code: 'session/not-found' } },
      create: async request => { makeAgent(request.sessionId) } },
    sessionPersistence: { flush: async () => { if (failParentFlush) throw new Error('temporary flush error') } }, permissionPresets: { current: () => 'read-only', set() {} } }
  const installed = installWorkers(ctx, { directory: dir, defineTool: tool => tool, bridge: async () => ({ output: '[]' }) })
  t.after(() => events.get('dispose')())
  let invocation = 0
  const call = (args, agent = parent) => tools.get('mobile_workers').execute(args, { agent, callId: `call-${++invocation}` })
  const start = JSON.parse((await call({ action: 'start', task: 'ask color', mode: 'read' })).text)
  parent.steer({ role: 'user', content: [{ type: 'text', text: 'review' }], source: { kind: 'plugin', plugin: 'dsh-answer-reviewer', note: 'parent' } })
  assert.deepEqual(parent.lastSteer.source, { kind: 'dsh-answer-reviewer', note: 'parent' })
  await new Promise(resolve => setImmediate(resolve))
  const row = installed.queue.rows.get(start.id), child = agents.get(row.sessionId)
  assert.equal(row.state, 'running')
  assert.deepEqual(child.inbox.nextTurn[0].source, { kind: 'mobile-workers' })
  assert.equal(row.inputs[0].delivery, 'delivered')
  events.get('session/event')(child.session, { type: 'user/message', data: child.inbox.nextTurn[0] })
  assert.ok(row.inputs[0].readAt)
  child.steer({ role: 'user', content: [{ type: 'text', text: 'review' }], source: { kind: 'plugin', plugin: 'dsh-answer-reviewer', note: 'steer-1/5' } })
  assert.deepEqual(child.lastSteer.source, { kind: 'dsh-answer-reviewer', note: 'steer-1/5' })
  assert.equal(child.guards[0]({ name: 'mobile_knowledge_read' }), undefined)
  assert.match(child.guards[0]({ name: 'mobile_device_tap' }), /不允许/)
  events.get('session/event')(child.session, { type: 'tool/call', data: { callId: 'bad', name: 'bash' } })
  events.get('session/event')(child.session, { type: 'tool/result', data: { message: { toolCallId: 'bad', isError: true } } })
  events.get('session/event')(child.session, { type: 'tool/call', data: { callId: 'ok', name: 'read' } })
  events.get('session/event')(child.session, { type: 'tool/result', data: { message: { toolCallId: 'ok', isError: false } } })
  failParentFlush = true
  tools.get('mobile_worker_report').execute({ state: 'waiting', summary: '哪种颜色？' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(row.state, 'waiting')
  await installed.syncDomainReports()
  assert.equal(row.outbox[0].delivery, 'pending')
  assert.equal(new WorkerQueue(dir).rows.get(row.id).outbox[0].delivery, 'pending')
  failParentFlush = false
  await installed.flushNotifications(row)
  assert.equal(row.outbox[0].delivery, 'delivered')
  assert.equal(parent.inbox.nextTurn.length, 1)
  assert.equal(row.reports[0].question, '哪种颜色？')
  assert.deepEqual(row.toolEvidence, { bash: { calls: 1, failures: 1 }, read: { calls: 1, failures: 0 } })
  assert.match(parent.inbox.nextTurn[0].content[0].text, /"bash":\{"calls":1,"failures":1\}/)
  assert.match(parent.inbox.nextTurn[0].content[0].text, /哪种颜色/)
  await call({ action: 'reply', id: row.id, task: '蓝色' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(row.state, 'running')
  assert.equal(child.inbox.nextTurn[1].content[0].text, '蓝色')
  assert.equal(row.inputs[1].kind, 'reply')
  assert.equal(row.inputs[1].delivery, 'delivered')
  assert.equal(row.reports[0].state, 'waiting')
  tools.get('mobile_worker_report').execute({ state: 'completed', summary: '蓝色确认' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 2, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(row.state, 'completed')
  assert.deepEqual(row.reports.map(item => item.state), ['waiting', 'completed'])
  await installed.syncDomainReports()
  await installed.superagent.coordinator.pump()
  const reloaded = new WorkerQueue(dir).rows.get(row.id)
  assert.deepEqual(reloaded.reports.map(item => item.state), ['waiting', 'completed'])
  assert.deepEqual(reloaded.inputs.map(item => item.kind), ['request', 'reply'])
  assert.equal(parent.inbox.nextTurn.length, 2)
  assert.match(parent.inbox.nextTurn[1].content[0].text, /蓝色确认/)
  // A completed task can receive an explicit update; subsequent inputs wait in order.
  await call({ action: 'add', id: row.id, task: '补充一' })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(row.state, 'running')
  assert.equal(child.inbox.nextTurn[2].content[0].text, '补充一')
  const add = { action: 'add', id: row.id, task: '补充二' }
  await tools.get('mobile_workers').execute(add, { agent: parent, callId: 'same-update' })
  await tools.get('mobile_workers').execute(add, { agent: parent, callId: 'same-update' })
  await call({ action: 'add', id: row.id, task: '补充三' })
  assert.equal(row.inputs.filter(item => item.kind === 'update' && item.delivery === 'pending').length, 2)
  assert.equal(child.inbox.nextTurn.length, 3)
  tools.get('mobile_worker_report').execute({ state: 'completed', summary: '一已处理' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 3, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  await installed.syncDomainReports()
  await installed.superagent.coordinator.pump()
  assert.equal(installed.superagent.domain.repo.tickets.get(row.domainTicketId).ended, undefined)
  assert.notEqual(row.state, 'cancelled')
  assert.equal(child.inbox.nextTurn[3].content[0].text, '补充二')
  tools.get('mobile_worker_report').execute({ state: 'completed', summary: '二已处理' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 4, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(child.inbox.nextTurn[4].content[0].text, '补充三')
  tools.get('mobile_worker_report').execute({ state: 'completed', summary: '三已处理' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 5, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(row.state, 'completed')
  assert.deepEqual(row.inputs.filter(item => item.kind === 'update').map(item => item.text), ['补充一', '补充二', '补充三'])
  assert.deepEqual(row.reports.map(item => item.state), ['waiting', 'completed', 'completed', 'completed', 'completed'])
  assert.deepEqual(row.outbox.map(item => item.revision), [1, 2, 3, 4, 5])
  assert.ok(row.outbox.every(item => item.delivery === 'delivered'))
  assert.deepEqual(new WorkerQueue(dir).rows.get(row.id).outbox.map(item => item.delivery), Array(5).fill('delivered'))
  await call({ action: 'add', id: row.id, task: '再做一轮' })
  await new Promise(resolve => setImmediate(resolve))
  await call({ action: 'add', id: row.id, task: '待取消' })
  tools.get('mobile_worker_report').execute({ state: 'progress', summary: '正在核对' }, { agent: child })
  assert.equal(row.state, 'running')
  assert.equal(row.progress, '正在核对')
  assert.equal(child.guards[0]({ name: 'read' }), undefined)
  tools.get('mobile_worker_report').execute({ state: 'blocked', summary: '缺少必要权限' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 6, reason: { kind: 'completed' } } })
  assert.equal(row.state, 'blocked')
  assert.equal(row.reports.at(-1).state, 'blocked')
  await call({ action: 'cancel', id: row.id })
  assert.equal(row.state, 'cancelled')
  assert.equal(row.inputs.find(item => item.text === '待取消').delivery, 'cancelled')
})
