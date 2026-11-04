// Model-facing read views of committed domain records. Worker identity comes from the scheduler.
const ownResources = new Set(['ticket', 'inputs', 'assignments', 'result'])
const brief = (text, limit = 400) => typeof text === 'string' ? text.slice(0, limit) : ''
const card = ticket => ({ id: ticket.id, title: brief(ticket.title), attention: ticket.attention?.kind,
  assignment: ticket.assignments?.at(-1)?.state, ended: ticket.ended?.outcome, updatedAt: ticket.updatedAt,
  scope: brief(ticket.mandate?.scope), decisionId: ticket.decisionId, triggerId: ticket.origin?.triggerId })

export function readSuperagentResource(domain, args, worker) {
  const { resource } = args
  const id = args.id ?? worker?.domainTicketId
  if (worker && (!worker.domainTicketId || id !== worker.domainTicketId || !ownResources.has(resource)))
    throw new Error('Worker 只可读取本工单的任务范围、输入、执行与结果')
  const offset = args.offset ?? 0, limit = args.limit ?? 10
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isInteger(limit) || limit < 1 || limit > 25) throw new Error('分页参数无效')
  const page = (rows, project) => ({ resource, items: rows.slice(offset, offset + limit).map(project),
    nextOffset: rows.length - offset > limit ? offset + limit : null, total: rows.length })
  const newest = (map, at) => [...map.values()].sort((a, b) => String(at(b) || '').localeCompare(String(at(a) || '')) ||
    String(a.id ?? a.key).localeCompare(String(b.id ?? b.key)))
  const repo = domain.repo
  if (resource === 'tickets') return page(newest(repo.ticketRows, t => t.updatedAt), card)
  if (resource === 'threads') return page(newest(repo.threadRows, t => t.lastAt ?? t.createdAt), t => ({
    key: t.key, pending: t.pending.length, evalId: t.evaluating?.id, tickets: t.subscriptions.slice(0, 25), lastAt: t.lastAt }))
  if (resource === 'decisions') return page(newest(repo.decisionRows, d => d.at), d => ({
    id: d.id, triggerIds: d.triggerIds.slice(0, 25), ticketId: d.ticketId, kind: d.kind, at: d.at,
    policyVersion: d.policyVersion, evidence: brief(d.audit?.evidence) }))
  if (resource === 'health') return { resource, pendingEffects: domain.pendingEffects().length,
    pendingReplies: domain.pendingMessages().length, retries: domain.pendingEffects().filter(item => item.attempts > 0).slice(0, 25)
      .map(item => ({ kind: item.kind, attempts: item.attempts, nextAttemptAt: item.nextAttemptAt })) }
  if (!ownResources.has(resource)) throw new Error('不支持的工单资源')
  const ticket = repo.tickets.get(id)
  if (!ticket) throw new Error('找不到工单')
  if (resource === 'ticket') return card(ticket)
  if (resource === 'inputs') return page([...ticket.inputs].reverse(), item => {
    const trigger = item.kind === 'trigger' ? repo.triggers.get(item.triggerId) : undefined
    return { id: item.id, kind: item.kind, source: trigger?.source, text: brief(trigger?.text ?? item.text, 1200) }
  })
  const assignment = item => ({ id: item.id, state: item.state, report: item.report && {
    state: item.report.state, summary: brief(item.report.summary, 1600), at: item.report.at } })
  if (resource === 'assignments') return page([...ticket.assignments].reverse(), assignment)
  const last = [...ticket.assignments].reverse().find(item => item.report)
  return { resource, ticketId: id, latest: last ? assignment(last) : null }
}
