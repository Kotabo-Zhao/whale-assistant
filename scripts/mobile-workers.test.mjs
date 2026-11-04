import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WorkerQueue, installWorkers, heartbeatTier, activeRowCount, readLaunchBudget, workerToolAllowed, HEARTBEAT_ACTIVE_MS, HEARTBEAT_CHANNEL_MS, HEARTBEAT_IDLE_MS } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/workers.js'

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'mobile-workers-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  return { queue: new WorkerQueue(dir), dir }
}
test('heartbeat tier: active rows/pending deliveries 5 s, channels 15 s, idle 60 s', () => {
  assert.equal(HEARTBEAT_ACTIVE_MS, 5000)
  assert.equal(HEARTBEAT_CHANNEL_MS, 15000)
  assert.equal(HEARTBEAT_IDLE_MS, 60000)
  assert.equal(heartbeatTier({}), HEARTBEAT_IDLE_MS)
  assert.equal(heartbeatTier({ channels: true }), HEARTBEAT_CHANNEL_MS)
  assert.equal(heartbeatTier({ hasTasks: true }), HEARTBEAT_CHANNEL_MS)
  assert.equal(heartbeatTier({ tasks: 1 }), HEARTBEAT_ACTIVE_MS)
  assert.equal(heartbeatTier({ mirrors: 1, channels: true }), HEARTBEAT_ACTIVE_MS)
  // Before the first settings snapshot the channel state is unknown; do not idle for a minute.
  assert.equal(heartbeatTier({ settingsReady: false }), HEARTBEAT_ACTIVE_MS)
  assert.equal(heartbeatTier({ tasks: 1, settingsReady: false }), HEARTBEAT_ACTIVE_MS)
  // History rows are settled: completed/failed/cancelled/interrupted do not keep the 5 s tier.
  assert.equal(activeRowCount([{ state: 'completed' }, { state: 'failed' }, { state: 'cancelled' }, { state: 'interrupted' }]), 0)
  assert.equal(activeRowCount([{ state: 'queued' }, { state: 'starting' }, { state: 'running' }, { state: 'cancelling' }, { state: 'waiting' }, { state: 'blocked' }]), 6)
  assert.equal(heartbeatTier({ tasks: activeRowCount([{ state: 'completed' }]) }), HEARTBEAT_IDLE_MS)
  // A report whose outbox receipt is still pending keeps the 5 s tier.
  assert.equal(heartbeatTier({ pendingDeliveries: 1 }), HEARTBEAT_ACTIVE_MS)
})

test('dependent work waits for all successful predecessors', t => {
  const { queue } = fixture(t)
  queue.save({ id: 'first', mode: 'read', state: 'running', owner: 'p', sessionId: 'first' })
  queue.save({ id: 'second', mode: 'read', state: 'queued', owner: 'p', sessionId: 'second', dependsOn: ['first'] })
  assert.deepEqual(queue.runnable().map(row => row.id), [])
  queue.save({ ...queue.rows.get('first'), state: 'failed' })
  assert.deepEqual(queue.runnable().map(row => row.id), [])
  queue.save({ ...queue.rows.get('first'), state: 'completed' })
  assert.deepEqual(queue.runnable().map(row => row.id), ['second'])
})

test('load pressure delays starts without limiting task creation', () => {
  assert.equal(readLaunchBudget(100, 2, 8), Infinity)
  assert.equal(readLaunchBudget(1, 20, 8), 0)
  assert.equal(readLaunchBudget(0, 20, 8), 1)
  assert.equal(readLaunchBudget(0, 1, 8, { thermal: 3 }), 0)
  assert.equal(readLaunchBudget(1, 1, 8, { conserve: true }), 0)
  assert.equal(readLaunchBudget(0, 1, 8, { thermal: 2 }), 1)
})

test('higher priority queued work starts before other eligible work', t => {
  const { queue } = fixture(t)
  queue.save({ id: 'low', mode: 'device', state: 'queued', owner: 'p', sessionId: 'low', priority: 1 })
  queue.save({ id: 'high', mode: 'device', state: 'queued', owner: 'p', sessionId: 'high', priority: 8 })
  assert.deepEqual(queue.runnable().map(row => row.id), ['high'])
})

test('a restarted queue retains the dependency and priority of a task', t => {
  const { queue, dir } = fixture(t)
  queue.save({ id: 'before', mode: 'read', state: 'waiting', owner: 'p', sessionId: 'before' })
  queue.save({ id: 'after', mode: 'read', state: 'queued', owner: 'p', sessionId: 'after', group: '目标', dependsOn: ['before'], priority: 7 })
  const restored = new WorkerQueue(dir)
  assert.equal(restored.rows.get('after').group, '目标')
  assert.equal(restored.rows.get('after').priority, 7)
  assert.deepEqual(restored.runnable(), [])
})

test('worker tool policy: read mode can query device status but never touch the device', () => {
  // 纯读取工具两种模式都允许。
  for (const name of ['read', 'grep', 'web_search', 'mobile_knowledge_read', 'mobile_task_search'])
    assert.equal(workerToolAllowed('read', name), true, name)
  // 设备只读状态查询：read 模式必须允许（此前缺失导致「只读查电量」必然 blocked）。
  for (const name of ['mobile_device_battery', 'mobile_device_current_app', 'mobile_device_info',
    'mobile_device_list_packages', 'mobile_device_get_setting', 'mobile_device_capabilities',
    'mobile_device_background_tasks', 'mobile_device_wait'])
    assert.equal(workerToolAllowed('read', name), true, name)
  // 设备操作与屏幕/文件类：read 模式一律拒绝。
  for (const name of ['mobile_device_tap', 'mobile_device_input_text', 'mobile_device_app_launch',
    'mobile_device_screenshot', 'mobile_device_ui_dump', 'mobile_device_shell', 'mobile_see_now',
    'mobile_device_file_read', 'mobile_device_file_write', 'mobile_device_task'])
    assert.equal(workerToolAllowed('read', name), false, name)
  // device 模式在此之上允许操作类工具。
  for (const name of ['mobile_device_tap', 'mobile_device_app_launch', 'mobile_device_screenshot',
    'mobile_device_ui_dump', 'mobile_device_shell', 'mobile_device_file_write', 'mobile_device_battery'])
    assert.equal(workerToolAllowed('device', name), true, name)
  // 未知工具默认拒绝，报告工具始终允许。
  assert.equal(workerToolAllowed('device', 'bash'), false)
  assert.equal(workerToolAllowed('read', 'mobile_worker_report'), true)
})

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
  const chatReceipts = process.env.DSH_WORKER_RECEIPT_MODE === 'chat'
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
  assert.equal(row.outbox[0].delivery, chatReceipts ? 'pending' : 'suppressed')
  assert.equal(new WorkerQueue(dir).rows.get(row.id).outbox[0].delivery, chatReceipts ? 'pending' : 'suppressed')
  failParentFlush = false
  await installed.flushNotifications(row)
  assert.equal(row.outbox[0].delivery, chatReceipts ? 'delivered' : 'suppressed')
  assert.equal(parent.inbox.nextTurn.length, chatReceipts ? 1 : 0)
  assert.equal(row.reports[0].question, '哪种颜色？')
  assert.deepEqual(row.toolEvidence, { bash: { calls: 1, failures: 1 }, read: { calls: 1, failures: 0 } })
  if (chatReceipts) {
    assert.match(parent.inbox.nextTurn[0].content[0].text, /"bash":\{"calls":1,"failures":1\}/)
    assert.match(parent.inbox.nextTurn[0].content[0].text, /哪种颜色/)
  }
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
  assert.equal(parent.inbox.nextTurn.length, chatReceipts ? 2 : 0)
  if (chatReceipts) assert.match(parent.inbox.nextTurn[1].content[0].text, /蓝色确认/)
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
  assert.ok(row.outbox.every(item => item.delivery === (chatReceipts ? 'delivered' : 'suppressed')))
  assert.deepEqual(new WorkerQueue(dir).rows.get(row.id).outbox.map(item => item.delivery), Array(5).fill(chatReceipts ? 'delivered' : 'suppressed'))
  const nextParent = makeAgent('session-next')
  events.get('session/event')(nextParent.session, { type: 'user/message', data: { id: 'new-topic', source: { kind: 'user' } } })
  const shared = JSON.parse((await call({ action: 'status' }, nextParent)).text)
  assert.ok(shared.some(item => item.id === row.id && item.owner === parent.id))
  await call({ action: 'add', id: row.id, task: '再做一轮' }, nextParent)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(child.inbox.nextTurn.at(-1).content[0].text, '再做一轮')
  await call({ action: 'add', id: row.id, task: '待取消' }, nextParent)
  tools.get('mobile_worker_report').execute({ state: 'progress', summary: '正在核对' }, { agent: child })
  assert.equal(row.state, 'running')
  assert.equal(row.progress, '正在核对')
  assert.equal(child.guards[0]({ name: 'read' }), undefined)
  tools.get('mobile_worker_report').execute({ state: 'blocked', summary: '缺少必要权限' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 6, reason: { kind: 'completed' } } })
  await new Promise(resolve => setImmediate(resolve))
  await installed.flushNotifications(row)
  if (chatReceipts) assert.match(nextParent.inbox.nextTurn.at(-1).content[0].text, /缺少必要权限/)
  else assert.equal(nextParent.inbox.nextTurn.length, 0)
  assert.equal(row.state, 'blocked')
  assert.equal(row.reports.at(-1).state, 'blocked')
  await call({ action: 'cancel', id: row.id }, nextParent)
  assert.equal(row.state, 'cancelled')
  assert.equal(row.inputs.find(item => item.text === '待取消').delivery, 'cancelled')
})

test('a creation worker has only its draft and reporting tools; restart requires an explicit resume', t => {
  for (const name of ['mobile_whale_feature', 'mobile_worker_report']) assert.equal(workerToolAllowed('create', name), true)
  for (const name of ['bash', 'read', 'mobile_knowledge_read', 'mobile_device_tap', 'mobile_device_info', 'mobile_workers'])
    assert.equal(workerToolAllowed('create', name), false, name)
  for (const mode of ['read', 'device']) assert.equal(workerToolAllowed(mode, 'mobile_whale_feature'), false)
  const { queue, dir } = fixture(t)
  queue.save({ id: 'creator', mode: 'create', owner: 'desk', sessionId: 'session-worker-creator', state: 'running', revision: 1, input: 'draft' })
  const restored = new WorkerQueue(dir).rows.get('creator')
  assert.equal(restored.state, 'interrupted')
  assert.equal(restored.revision, 1)
})
