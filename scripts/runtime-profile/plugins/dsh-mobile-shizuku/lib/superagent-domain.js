import { mkdirSync, readFileSync, renameSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { Engine, MemoryRepositories } from './superagent-core.js'

// Upstream domain engine, with one atomic snapshot for its repositories and its queued effects.
// The transport and executor consume effects after commit; they must acknowledge by id.
export class SuperagentDomain {
  constructor(directory, policy = { version: 'phone-v1', sources: {} }) {
    mkdirSync(directory, { recursive: true })
    this.path = join(directory, 'domain.json')
    this.policyConfig = structuredClone(policy)
    this.repo = new MemoryRepositories()
    this.effects = new Map()
    this.messages = new Map()
    const fields = ['triggerRows', 'dispositions', 'threadRows', 'ticketRows', 'decisionRows']
    if (existsSync(this.path)) {
      const text = readFileSync(this.path, 'utf8')
      const state = JSON.parse(text)
      this.committedDigest = createHash('sha256').update(text).digest('hex')
      for (const field of fields) this.repo[field] = new Map(state[field] ?? [])
      this.effects = new Map(state.effects ?? [])
      this.messages = new Map(state.messages ?? [])
      this.policyConfig = state.policy ?? this.policyConfig
    }
    const atomic = this.repo.atomic.bind(this.repo)
    let depth = 0
    this.repo.atomic = fn => {
      const effects = structuredClone(this.effects)
      const messages = structuredClone(this.messages)
      const policy = this.policyConfig
      depth++
      try {
        return atomic(() => {
          const result = fn()
          if (depth === 1) this.persist(fields)
          return result
        })
      } catch (error) {
        this.effects = effects
        this.messages = messages
        this.policyConfig = policy
        throw error
      } finally { depth-- }
    }
    const enqueue = (kind, id, payload) => {
      const key = `${kind}:${id}`
      const existing = this.effects.get(key)
      if (!existing || JSON.stringify(existing.payload) !== JSON.stringify(payload)) {
        this.effects.set(key, { id: key, kind, payload, createdAt: Date.now(), generation: randomUUID() })
      }
    }
    const config = () => this.policyConfig
    const sourceAllowed = (source, actor) => {
      if (source === 'phone') return true // only the authenticated local session adapter can create phone triggers
      const rule = config().sources?.[source]
      const normalized = source === 'github' ? actor.toLowerCase() : actor
      return !!rule?.enabled && Array.isArray(rule.allowedActors) && rule.allowedActors.some(item => (source === 'github' ? item.toLowerCase() : item) === normalized)
    }
    this.isAllowed = sourceAllowed
    const policyForEngine = () => ({
      version: config().version, paused: !!config().paused, evalTimeoutMs: 120000,
      gate: trigger => {
        if (!sourceAllowed(trigger.source, trigger.actor.id) || !trigger.human) return { admissible: false, reason: 'senderNotAllowed' }
        if (trigger.addressed) return { admissible: true, strength: 'explicit', basis: { via: 'addressed', addressed: trigger.addressed, sender: trigger.actor.id, source: trigger.source } }
        const mode = config().proactive?.mode ?? 'off'
        if (mode === 'off' || !(config().proactive?.rules ?? []).length) return { admissible: false, reason: 'notAddressed' }
        return { admissible: true, strength: 'inferred', basis: { via: 'admission', cell: 'listedUnaddressed', sender: trigger.actor.id, source: trigger.source, observation: { mode } } }
      },
      revalidate: mandate => sourceAllowed(mandate.actor.source, mandate.actor.id)
        && (mandate.strength !== 'inferred' || ((config().proactive?.mode === 'auto') && (mandate.basis.rules ?? []).length > 0 && (mandate.basis.rules ?? []).every(id => (config().proactive?.rules ?? []).some(rule => rule.id === id)))),
      admitsInferred: rules => config().proactive?.mode === 'auto' && rules.length > 0
        && rules.every(id => (config().proactive?.rules ?? []).some(rule => rule.id === id)),
    })
    this.engine = new Engine({ repo: this.repo, policy: policyForEngine,
      scope: { scope: ({ source, meta }) => {
        const rule = config().sources?.[source]
        const allowed = source === 'phone' || (rule?.enabled && (source === 'feishu' ? rule.chats?.includes(meta.chat)
          : source === 'github' ? rule.repo?.toLowerCase() === meta.repo?.toLowerCase() : false))
        if (!allowed) return { listened: false, reason: 'sourceNotConfigured' }
        if (meta.needsApproval) return { listened: false, reason: 'actorNotApproved' }
        return { listened: true, observed: false }
      } },
      assessor: { request: candidate => enqueue('assess', candidate.evalId, candidate), release: id => this.effects.delete(`assess:${id}`) },
      executor: {
        start: (ticket, assignment) => enqueue('start', assignment.id, { ticket, assignment }),
        deliver: (ticket, assignment) => enqueue('deliver', `${assignment.id}:${ticket.undelivered.join(',')}`, { ticket, assignment }),
        stop: (ticket, assignment, reason) => enqueue('stop', assignment.id, { ticketId: ticket.id, assignmentId: assignment.id, reason }),
      },
      outbox: {
        send: message => { if (!this.messages.has(message.id)) this.messages.set(message.id, { ...message, status: 'pending' }) },
        status: id => this.messages.get(id)?.status,
        withdraw: id => { const row = this.messages.get(id); if (row?.status === 'pending') row.status = 'dropped' },
      },
    })
  }
  persist(fields = ['triggerRows', 'dispositions', 'threadRows', 'ticketRows', 'decisionRows']) {
    const state = Object.fromEntries(fields.map(field => [field, [...this.repo[field]]]))
    state.effects = [...this.effects]; state.messages = [...this.messages]; state.policy = this.policyConfig
    const text = JSON.stringify(state)
    const digest = createHash('sha256').update(text).digest('hex')
    if (digest === this.committedDigest) return
    writeFileSync(`${this.path}.tmp`, text, { mode: 0o600, flush: true })
    renameSync(`${this.path}.tmp`, this.path)
    this.committedDigest = digest
  }
  configure(policy) {
    if (!policy?.version || !policy.sources || typeof policy.sources !== 'object') throw new Error('无效的来源策略')
    const previous = this.policyConfig
    this.policyConfig = structuredClone(policy)
    try {
      const effects = new Map()
      for (const ticket of this.repo.tickets.open()) {
        const effect = {}
        if (!this.isAllowed(ticket.mandate.actor.source, ticket.mandate.actor.id)) {
          effect.requesterAllowed = false; effect.requester = ticket.mandate.actor
        }
        if (ticket.mandate.strength === 'inferred') {
          if (policy.proactive?.mode !== 'auto') effect.proactive = false
          const before = previous.proactive?.rules ?? [], after = policy.proactive?.rules ?? []
          const changed = (ticket.mandate.basis.rules ?? []).flatMap(id => {
            const original = before.find(item => item.id === id), current = after.find(item => item.id === id)
            if (!current) return [{ id, name: original?.text ?? id, change: 'disabled' }]
            if (original && current.text !== original.text) return [{ id, name: current.text, change: 'changed', instruction: current.text }]
            return []
          })
          if (changed.length) effect.rules = changed
        }
        if (Object.keys(effect).length) effects.set(ticket.id, effect)
      }
      this.engine.policyChanged(previous.version, policy.version, effects)
    } catch (error) { this.policyConfig = previous; throw error }
  }
  receive(trigger) {
    if (!trigger?.source || trigger.actor?.source !== trigger.source || !trigger.id || !Array.isArray(trigger.threads) || trigger.threads.some(thread => thread.source !== trigger.source)) throw new Error('来源身份不一致')
    if (this.repo.triggers.get(trigger.id)) return 'duplicate'
    if (trigger.source !== 'phone' && trigger.human && !this.isAllowed(trigger.source, trigger.actor.id)) {
      trigger = { ...trigger, meta: { ...trigger.meta, needsApproval: true } }
    }
    const result = this.engine.receive(trigger); this.engine.evaluateDue(); return result
  }
  decide(triggerId, outcome) { const decision = this.engine.decide(triggerId, outcome); this.engine.evaluateDue(); return decision }
  revokeTicket(ticketId) {
    return this.repo.atomic(() => {
      const ticket = this.repo.tickets.get(ticketId)
      if (!ticket) throw new Error('工单不存在')
      if (!ticket.ended) this.engine.stopTicket(ticketId, '用户撤回任务授权')
      const current = this.repo.tickets.get(ticketId)
      if (current.ended.by !== 'human') {
        current.previousEnd = current.ended
        current.ended = { at: new Date().toISOString(), by: 'human', outcome: 'stopped', reason: '用户撤回任务授权' }
        current.updatedAt = current.ended.at
        this.repo.tickets.save(current)
      }
      for (const message of this.messages.values()) {
        if (message.ticketId === ticketId && message.status === 'pending') message.status = 'dropped'
      }
      return current
    })
  }
  // A person can approve a known sender in settings without granting a whole chat or mapping identities across services.
  unapprovedSenders() {
    const result = new Map()
    for (const trigger of this.repo.triggerRows.values()) {
      const filtered = this.repo.triggers.disposition(trigger.id)?.filtered
      if (!trigger.human || trigger.source === 'phone' || (filtered && filtered.code !== 'actorNotApproved')) continue
      if (!trigger.addressed && !trigger.threads.some(thread => this.repo.threads.get(`${thread.source}:${thread.id}`)?.subscriptions?.length)) continue
      const source = this.policyConfig.sources?.[trigger.source]
      if (!source?.enabled || source.allowedActors?.includes(trigger.actor.id)) continue
      const key = `${trigger.source}:${trigger.actor.id}`
      result.set(key, { source: trigger.source, actorId: trigger.actor.id })
    }
    return [...result.values()]
  }
  authorizeSender(source, actorId) {
    const policy = structuredClone(this.policyConfig), rule = policy.sources?.[source]
    if (!rule?.enabled || typeof actorId !== 'string' || !actorId) throw new Error('来源或发送人无效')
    rule.allowedActors = [...new Set([...(rule.allowedActors ?? []), actorId])]
    policy.version = `${policy.version}:${Date.now()}`
    this.configure(policy)
  }
  pendingEffects() { return structuredClone([...this.effects.values()]) }
  acknowledgeEffect(id, generation) {
    const effect = this.effects.get(id)
    if (!effect || (generation !== undefined && (effect.generation ?? 0) !== generation)) return
    this.repo.atomic(() => this.effects.delete(id))
  }
  failEffect(id, generation, error, now = Date.now()) {
    this.repo.atomic(() => {
      const effect = this.effects.get(id)
      if (!effect || (effect.generation ?? 0) !== generation) return
      effect.attempts = (effect.attempts ?? 0) + 1
      effect.lastError = String(error?.message ?? error).slice(0, 300)
      effect.nextAttemptAt = now + Math.min(300000, 5000 * 2 ** Math.min(effect.attempts - 1, 6))
    })
  }
  pendingMessages() { return structuredClone([...this.messages.values()].filter(row => row.status === 'pending')) }
  markMessage(id, status) {
    if (!['sent', 'dropped'].includes(status)) throw new Error('无效的消息回执')
    this.repo.atomic(() => { const row = this.messages.get(id); if (row?.status === 'pending') row.status = status })
  }
  failMessage(id, error, now = Date.now()) {
    this.repo.atomic(() => {
      const row = this.messages.get(id)
      if (!row || row.status !== 'pending') return
      row.attempts = (row.attempts ?? 0) + 1
      row.lastError = String(error?.message ?? error).slice(0, 300)
      row.nextAttemptAt = now + Math.min(300000, 5000 * 2 ** Math.min(row.attempts - 1, 6))
    })
  }
}
