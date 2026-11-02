import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { SuperagentDomain } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-domain.js'
import { SuperagentCoordinator } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-coordinator.js'
import { feishuTrigger, githubReviewTrigger } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-sources.js'
import { installWorkers } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/workers.js'
import { SuperagentSourceRunner } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-runner.js'
import { relatedFeishuTickets } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-runtime.js'

test('related Feishu tickets only expose same sender and chat context', () => {
  const origins = new Map([
    ['a', { source: 'feishu', actor: { id: 'alice' }, meta: { chat: 'group' }, text: 'first' }],
    ['b', { source: 'feishu', actor: { id: 'bob' }, meta: { chat: 'group' }, text: 'private' }],
    ['c', { source: 'feishu', actor: { id: 'alice' }, meta: { chat: 'other' }, text: 'elsewhere' }],
  ])
  const tickets = [...origins.keys()].map(id => ({ id, origin: { triggerId: id }, title: id }))
  const domain = { repo: { tickets: { list: () => tickets }, triggers: { get: id => origins.get(id) } },
    messages: new Map([['reply', { ticketId: 'a', status: 'sent', text: 'answer' }]]) }
  const result = relatedFeishuTickets(domain, [{ source: 'feishu', human: true, actor: { id: 'alice' }, meta: { chat: 'group' } }])
  assert.deepEqual(result.map(item => item.id), ['a'])
  assert.deepEqual(result[0].replies, ['answer'])
})

const event = (id, actor, text, root = 'om_first') => feishuTrigger({ message: { chat_id: 'oc_test', message_id: id, root_id: root,
  chat_type: 'group', msg_type: 'text', create_time: '1793600000000', body: { content: JSON.stringify({ text }) },
  mentions: [{ id: 'ou_bot' }], thread_id: 'omt_thread' }, sender: { id: actor, sender_type: 'user' } }, 'ou_bot')

test('source identities are approved separately and a ticket survives followup and restart', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-domain-')); t.after(() => rmSync(directory, { recursive: true, force: true }))
  const domain = new SuperagentDomain(directory, { version: 'v1', sources: { feishu: { enabled: true, chats: ['oc_test'], allowedActors: [] } } })
  assert.equal(domain.receive(event('om_denied', 'ou_person', '查一下')), 'filtered')
  assert.deepEqual(domain.unapprovedSenders(), [{ source: 'feishu', actorId: 'ou_person' }])
  domain.authorizeSender('feishu', 'ou_person')
  const first = event('om_first', 'ou_person', '查一下资料')
  assert.equal(domain.receive(first), 'stored'); assert.equal(domain.receive(first), 'duplicate')
  const created = [], deliveries = []
  const scheduler = new SuperagentCoordinator(domain, { assess: async () => ({ kind: 'accept', title: '查资料', scope: '只读资料', reason: '用户请求' }),
    start: async (ticket, assignment) => { created.push([ticket.id, assignment.id]) },
    deliver: async (ticket, assignment, ids) => { deliveries.push([ticket.id, assignment.id, ids]); return true }, stop: async () => {} })
  await scheduler.pump(); assert.equal(created.length, 1)
  const id = created[0][0], ticket = domain.repo.tickets.get(id)
  assert.equal(ticket.mandate.scope, '只读资料'); assert.equal(ticket.mandate.actor.id, 'ou_person')
  assert.ok(domain.repo.threads.get('feishu:oc_test:om_first').subscriptions.includes(id))
  scheduler.report(id, { state: 'waiting', summary: '哪份资料？', question: '哪份资料？' })
  assert.equal(domain.pendingMessages().length, 1)
  domain.markMessage(domain.pendingMessages()[0].id, 'sent')
  const next = event('om_follow', 'ou_person', '蓝色那份')
  domain.receive(next); await scheduler.pump()
  assert.equal(deliveries.length, 1)
  scheduler.report(id, { state: 'ready', summary: '找到了蓝色资料' })
  assert.equal(domain.repo.tickets.get(id).ended.outcome, 'completed')
  const restored = new SuperagentDomain(directory)
  assert.equal(restored.repo.tickets.get(id).ended.outcome, 'completed')
  assert.equal(restored.pendingMessages().length, 1)
  const message = restored.pendingMessages()[0]; restored.markMessage(message.id, 'sent')
  assert.equal(new SuperagentDomain(directory).pendingMessages().length, 0)
})

test('review comments preserve their source thread and reject the bot itself', () => {
  const comment = { id: 15, user: { login: 'Alice', type: 'User' }, body: '@helper please inspect', created_at: '2026-11-02T00:00:00Z', in_reply_to_id: 12 }
  const trigger = githubReviewTrigger('owner/project', 3, comment, 'helper')
  assert.equal(trigger.addressed, 'mention'); assert.equal(trigger.actor.source, 'github')
  assert.equal(trigger.channel.address.review, '12'); assert.equal(trigger.threads.length, 2)
  assert.equal(githubReviewTrigger('owner/project', 3, { ...comment, user: { login: 'helper', type: 'Bot' } }, 'helper'), null)
})


test('a Feishu event is evaluated by the parent and executed by the existing Worker', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-integrated-')); t.after(() => rmSync(directory, { recursive: true, force: true }))
  const tools = new Map(), events = new Map(), agents = new Map()
  const makeAgent = id => {
    const agent = { id, options: { provider: 'test', model: 'test' },
      session: { id, requestHeader: () => ({ config: { provider: 'test', model: 'test' } }), snapshotEvents: () => [] },
      ctx: { tools: { guard() {} }, systemPrompt: { section() {} } }, inbox: { nextTurn: [], nextStep: [] },
      followup(message) { this.inbox.nextTurn.push(message) }, cancel() {} }
    agents.set(id, agent); return agent
  }
  const parent = makeAgent('session-parent')
  const ctx = { on: (name, fn) => events.set(name, fn), tools: { register: tool => tools.set(tool.name, tool), guard() {} }, systemPrompt: { section() {} },
    sessionController: { resolveAgent: async id => agents.has(id) ? { agent: agents.get(id) } : { error: { code: 'session/not-found' } },
      create: async req => { makeAgent(req.sessionId) } }, sessionPersistence: { flush: async () => {} },
    permissionPresets: { current: () => 'read-only', set() {} } }
  const installed = installWorkers(ctx, { directory, defineTool: tool => tool, bridge: async () => ({ output: '[]' }) })
  t.after(() => events.get('dispose')())
  await tools.get('mobile_workers').execute({ action: 'status' }, { agent: parent, callId: 'parent' })
  installed.superagent.domain.configure({ version: 'v2', sources: { feishu: { enabled: true, chats: ['oc_test'], allowedActors: ['ou_person'] } } })
  const trigger = event('om_first', 'ou_person', '搜索蓝色资料')
  installed.superagent.domain.receive(trigger)
  await installed.superagent.coordinator.pump()
  assert.match(parent.inbox.nextTurn[0].content[0].text, /跨渠道待评估事件/)
  const candidate = installed.superagent.domain.engine.candidate(trigger.id)
  await tools.get('mobile_superagent_decide').execute({ evalId: candidate.evalId, triggerId: trigger.id, decision: 'accept', title: '查资料', scope: '读取蓝色资料', reason: '用户请求', mode: 'read' }, { agent: parent })
  await new Promise(resolve => setImmediate(resolve)); await installed.superagent.coordinator.pump()
  const row = [...installed.queue.rows.values()].find(item => item.domainTicketId)
  assert.ok(row); assert.equal(row.owner, parent.id); assert.equal(row.state, 'running')
  const child = agents.get(row.sessionId)
  tools.get('mobile_worker_report').execute({ state: 'completed', summary: '已找到资料' }, { agent: child })
  events.get('session/event')(child.session, { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } })
  await installed.syncDomainReports()
  const pending = installed.superagent.domain.pendingMessages()
  assert.equal(pending.length, 1); assert.equal(pending[0].channel.source, 'feishu'); assert.equal(pending[0].text, '已找到资料')
  events.get('dispose')()
  const restarted = installWorkers(ctx, { directory, defineTool: tool => tool, bridge: async () => ({ output: '[]' }) })
  const second = event('om_second', 'ou_person', '另一个请求', 'om_second')
  restarted.superagent.domain.receive(second)
  await restarted.superagent.coordinator.pump()
  assert.ok(parent.inbox.nextTurn.some(message => message.content[0].text.includes('om_second')))
})


test('proactive work requires an enabled rule and auto mode', t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-policy-')); t.after(() => rmSync(directory, { recursive: true, force: true }))
  const source = { feishu: { enabled: true, chats: ['oc_test'], allowedActors: ['ou_person'] } }
  const domain = new SuperagentDomain(directory, { version: 'v1', sources: source, proactive: { mode: 'auto', rules: [{ id: 'rule-1', text: '整理资料' }] } })
  const trigger = event('om_rule', 'ou_person', '资料有新变化'); delete trigger.addressed
  assert.equal(domain.receive(trigger), 'stored')
  const candidate = domain.engine.candidate(trigger.id); assert.equal(candidate.strength, 'inferred')
  assert.throws(() => domain.decide(trigger.id, { kind: 'accept', title: '整理', scope: '只读整理', reason: '规则', evalId: candidate.evalId, rules: ['unknown'] }))
  const accepted = domain.decide(trigger.id, { kind: 'accept', title: '整理', scope: '只读整理', reason: '规则', evalId: candidate.evalId, rules: ['rule-1'] })
  assert.equal(accepted.mandate.strength, 'inferred'); assert.deepEqual(accepted.mandate.basis.rules, ['rule-1'])
})


test('policy changes are recorded on live assignments without silently broadening authority', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-policy-change-')); t.after(() => rmSync(directory, { recursive: true, force: true }))
  const source = { feishu: { enabled: true, chats: ['oc_test'], allowedActors: ['ou_person'] } }
  const domain = new SuperagentDomain(directory, { version: 'v1', sources: source })
  const trigger = event('om_policy', 'ou_person', '查询资料'); domain.receive(trigger)
  const candidate = domain.engine.candidate(trigger.id)
  const decision = domain.decide(trigger.id, { kind: 'accept', title: '查询', scope: '只读', reason: '请求', evalId: candidate.evalId })
  const ticket = domain.repo.tickets.get(decision.ticketId); domain.engine.assignmentStarted(ticket.id, ticket.assignments[0].id)
  domain.configure({ version: 'v2', sources: { feishu: { enabled: true, chats: ['oc_test'], allowedActors: [] } } })
  const changed = domain.repo.tickets.get(ticket.id)
  const policyInput = changed.inputs.find(input => input.kind === 'policy-changed')
  assert.equal(policyInput.effect.requesterAllowed, false)
  assert.ok(changed.undelivered.includes(policyInput.id))
  assert.equal(changed.mandate.scope, '只读')
})


test('discovers and polls a subscribed Feishu topic when the original event lacked a thread id', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-topic-')); t.after(() => rmSync(directory, { recursive: true, force: true }))
  const settings = { feishu: { enabled: true, chats: ['oc_test'], allowedActors: ['ou_person'] } }
  const domain = new SuperagentDomain(directory, { version: 'v1', sources: settings })
  const first = event('om_first', 'ou_person', '查询资料'); delete first.meta.threadId
  domain.receive(first); const candidate = domain.engine.candidate(first.id)
  const decision = domain.decide(first.id, { kind: 'accept', title: '查询', scope: '只读', reason: '请求', evalId: candidate.evalId })
  const ticket = domain.repo.tickets.get(decision.ticketId); domain.engine.assignmentStarted(ticket.id, ticket.assignments[0].id)
  const next = event('om_second', 'ou_person', '补充资料')
  let discovered = 0
  const channels = { githubComments: async () => [], githubIssuesAndReviews: async () => [],
    feishuDiscoverThread: async () => { discovered++; return 'omt_thread' },
    feishuHistory: async (_chat, _since, thread) => thread ? [next] : [], send: async () => {} }
  const runner = new SuperagentSourceRunner(directory, domain, channels, () => settings)
  await runner.poll()
  assert.equal(discovered, 1)
  assert.ok(domain.repo.tickets.get(ticket.id).inputs.some(input => input.kind === 'trigger' && input.triggerId === next.id))
})
