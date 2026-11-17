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

export const STALL_REPEATS = 3
export const STALL_QUIET_MS = 90_000

/**
 * 只根据已经记下的健康证据判断是否停滞。
 * 相同结果要先安静一段时间，进行中的工具、模型重试、未读追问和新进展都不算停滞。
 * 不在这里启动计时器，也不重新执行任务。
 */
export function assessWorkerStall(row, now = Date.now()) {
  if (row.state !== 'running' || row.reportState || row.authorizationRevoked) return null
  if (row.inputs?.some(input => input.kind === 'update' && !input.readAt)) return null
  const health = row.health
  if (!health) return null
  if (Number.isFinite(health.retryUntil) && now < health.retryUntil) return null
  if (health.pending && Object.keys(health.pending).length > 0) return null
  if ((health.repeated ?? 0) < STALL_REPEATS) return null
  if (!Number.isFinite(health.lastProgressAt) || now - health.lastProgressAt < STALL_QUIET_MS) return null
  return {
    revision: row.revision, runId: health.runId, sequence: health.sequence, heartbeatAt: health.heartbeatAt,
    reason: 'stalled', explanation: '连续得到相同结果，并且之后没有新进展。',
  }
}

/** 同一轮证据只发出一次暂停。新进展让 acceptsWatchdogHold 失败，因此不会重复暂停。 */
export function takeStallHold(row, now = Date.now()) {
  const stall = assessWorkerStall(row, now)
  if (!stall || !acceptsWatchdogHold(row, stall)) return null
  if (row.watchdog?.runId === stall.runId && row.watchdog.sequence === stall.sequence) return null
  row.watchdog = { commandId: `stall:${stall.runId}:${stall.sequence}`, ...stall, at: now }
  return stall
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
