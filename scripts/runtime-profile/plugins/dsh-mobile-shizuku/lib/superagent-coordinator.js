/** Drives the upstream Engine's durable effects through host-provided model and Worker adapters. */
export class SuperagentCoordinator {
  constructor(domain, { assess, start, deliver, stop }) {
    this.domain = domain; this.assess = assess; this.start = start; this.deliver = deliver; this.stop = stop
    this.busy = false; this.problems = new Map()
  }
  async pump() {
    if (this.busy) return
    this.busy = true
    try {
      this.domain.engine.evaluateDue()
      const visited = new Set()
      for (;;) {
        const effect = this.domain.pendingEffects().find(item => !visited.has(item.id) && (item.nextAttemptAt ?? 0) <= Date.now())
        if (!effect) break
        visited.add(effect.id)
        try {
          let finish
          if (effect.kind === 'assess') {
            const candidate = effect.payload
            const decision = await this.assess(candidate)
            if (!decision) continue
            if (!['accept', 'ignore', 'stop', 'unavailable'].includes(decision.kind)) throw new Error('评估结果无效')
            // The engine rejects decisions for an obsolete Eval and revalidates the current policy.
            finish = () => this.domain.decide(candidate.triggers[0].id, { ...decision, evalId: candidate.evalId })
          } else if (effect.kind === 'start') {
            const { ticket, assignment } = effect.payload
            // Host start must be idempotent by assignment.id and reconcile a prior uncertain launch.
            await this.start(ticket, assignment)
            finish = () => this.domain.engine.assignmentStarted(ticket.id, assignment.id)
          } else if (effect.kind === 'deliver') {
            const { ticket, assignment } = effect.payload
            const ids = ticket.undelivered
            const delivered = await this.deliver(ticket, assignment, ids)
            if (!delivered) continue
            finish = () => this.domain.engine.inputsDelivered(ticket.id, assignment.id, ids)
          } else if (effect.kind === 'stop') {
            await this.stop(effect.payload)
          } else throw new Error('未知派工效果')
          this.domain.repo.atomic(() => {
            finish?.()
            this.domain.acknowledgeEffect(effect.id, effect.generation ?? 0)
          })
          this.problems.delete(effect.id)
        } catch (error) {
          this.problems.set(effect.id, String(error?.message ?? error).slice(0, 300))
          this.domain.failEffect(effect.id, effect.generation ?? 0, error)
          if (effect.kind === 'deliver') {
            const { ticket, assignment } = effect.payload
            try { this.domain.engine.deliveryFailed(ticket.id, assignment.id, String(error?.message ?? error).slice(0, 300)) } catch {}
          }
        }
      }
    } finally { this.busy = false }
  }
  status() {
    return this.domain.pendingEffects().filter(effect => effect.lastError).map(effect => ({ id: effect.id, message: effect.lastError, attempts: effect.attempts }))
  }
  report(ticketId, report) {
    return this.domain.repo.atomic(() => {
      const ticket = this.domain.engine.report(ticketId, report)
      const external = this.domain.repo.triggers.get(ticket.origin.triggerId)?.source !== 'phone'
      if (external && report.state === 'ready') this.domain.engine.reply({ purpose: 'result', ticketId, text: report.summary })
      else if (external && report.question) this.domain.engine.reply({ purpose: 'attention', ticketId, question: report.question, text: report.question })
      return ticket
    })
  }
}
