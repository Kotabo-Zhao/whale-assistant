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
        const effect = this.domain.pendingEffects().find(item => !visited.has(item.id))
        if (!effect) break
        visited.add(effect.id)
        try {
          if (effect.kind === 'assess') {
            const candidate = effect.payload
            const decision = await this.assess(candidate)
            if (!decision) continue
            if (!['accept', 'ignore', 'stop', 'unavailable'].includes(decision.kind)) throw new Error('评估结果无效')
            // The engine rejects decisions for an obsolete Eval and revalidates the current policy.
            this.domain.decide(candidate.triggers[0].id, { ...decision, evalId: candidate.evalId })
          } else if (effect.kind === 'start') {
            const { ticket, assignment } = effect.payload
            // Host start must be idempotent by assignment.id and reconcile a prior uncertain launch.
            await this.start(ticket, assignment)
            this.domain.engine.assignmentStarted(ticket.id, assignment.id)
          } else if (effect.kind === 'deliver') {
            const { ticket, assignment } = effect.payload
            const ids = ticket.undelivered
            const delivered = await this.deliver(ticket, assignment, ids)
            if (!delivered) continue
            this.domain.engine.inputsDelivered(ticket.id, assignment.id, ids)
          } else if (effect.kind === 'stop') {
            await this.stop(effect.payload)
          } else throw new Error('未知派工效果')
          this.domain.acknowledgeEffect(effect.id)
          this.problems.delete(effect.id)
        } catch (error) { this.problems.set(effect.id, String(error?.message ?? error).slice(0, 300)) }
      }
    } finally { this.busy = false }
  }
  status() { return [...this.problems].map(([id, message]) => ({ id, message })) }
  report(ticketId, report) {
    const ticket = this.domain.engine.report(ticketId, report)
    const external = this.domain.repo.triggers.get(ticket.origin.triggerId)?.source !== 'phone'
    if (external && report.state === 'ready') this.domain.engine.reply({ purpose: 'result', ticketId, text: report.summary })
    else if (external && report.question) this.domain.engine.reply({ purpose: 'attention', ticketId, question: report.question, text: report.question })
    return ticket
  }
}
