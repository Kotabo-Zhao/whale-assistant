import { createHash, randomUUID } from 'node:crypto'

const digest = value => createHash('sha256').update(JSON.stringify(value) ?? '').digest('hex').slice(0, 24)

export function beginWorkerHealth(row, now = Date.now()) {
  row.health = { task: (row.input || row.title || "").slice(0, 1200), runId: randomUUID(), startedAt: now, heartbeatAt: now, sequence: 0,
    lastProgressAt: now, repeated: 0, recent: [], pending: {} }
}

/** Bounded evidence, independent of model prose. Tool arguments and results stay hashed. */
export function recordWorkerHealth(row, event, now = Date.now()) {
  const health = row.health
  if (!health || row.state !== 'running') return
  const data = event.data ?? {}
  if (event.type === 'llm/retry') {
    health.retryUntil = now + Math.min(120000, Math.max(5000, Number(data.delayMs) || 0))
    health.heartbeatAt = now
    return
  }
  if (event.type === 'user/message') {
    const input = row.inputs?.find(item => item.id === data.id)
    if (input) {
      health.sequence++
      health.lastProgressAt = now
      health.heartbeatAt = now
      health.task = (health.task + '\n用户补充：' + input.text).slice(-1200)
    }
    return
  }
  if (event.type === 'tool/call' && data.callId && data.name) {
    const args = data.args ?? data.arguments ?? data.input
    health.pending[data.callId] = { name: data.name, key: digest([data.name, args ?? data.callId]) }
    for (const key of Object.keys(health.pending).slice(0, -16)) delete health.pending[key]
    return
  }
  let sample
  if (event.type === 'tool/result') {
    const call = health.pending[data.message?.toolCallId]
    if (!call) return
    delete health.pending[data.message.toolCallId]
    const key = digest([call.key, data.message?.content, !!data.message?.isError])
    health.repeated = key === health.lastResult ? health.repeated + 1 : 1
    if (key !== health.lastResult) health.lastProgressAt = now
    health.lastResult = key
    sample = { kind: 'tool', tool: call.name, digest: key, error: !!data.message?.isError }
  } else if (event.type === 'assistant/message') {
    const content = data.message?.content?.filter(part => part.type === 'text').map(part => part.text).join('')
    if (!content) return
    sample = { kind: 'message', digest: digest(content) }
  } else return
  health.sequence++
  health.recent = health.recent.concat({ ...sample, sequence: health.sequence, at: now }).slice(-8)
  health.heartbeatAt = now
}

/** Reject assessments for a previous run or evidence superseded while the model was thinking. */
export function acceptsWatchdogHold(row, command) {
  return row.state === 'running' && !row.reportState && !row.authorizationRevoked &&
    !row.inputs?.some(input => input.kind === 'update' && !input.readAt) &&
    row.revision === command.revision && row.health?.runId === command.runId &&
    row.health.sequence === command.sequence &&
    (command.reason !== 'unresponsive' || row.health.heartbeatAt === command.heartbeatAt) &&
    ['stalled', 'unresponsive'].includes(command.reason) &&
    typeof command.explanation === 'string' && command.explanation.length > 0 && command.explanation.length <= 500
}
