import { useEffect, useMemo, useState } from 'react'
import { CheckCircle2, CircleDot, RefreshCw, XCircle } from 'lucide-react'
import { runtimeBridge } from '../platform/native'
import { companionRows, type CompanionRequest, type CompanionRow } from '../platform/companion'
import './AssistantTasks.css'

type TaskState = 'candidate' | 'open' | 'done' | 'cancelled'
const states: Array<{ id: TaskState | 'all'; label: string }> = [
  { id: 'all', label: '全部' }, { id: 'candidate', label: '待核实' }, { id: 'open', label: '待处理' }, { id: 'done', label: '已完成' }, { id: 'cancelled', label: '已取消' },
]
const label = (state: unknown): string => state === 'candidate' ? '待核实' : state === 'open' ? '待处理' : state === 'done' ? '已完成' : state === 'cancelled' ? '已取消' : '状态未知'
const validState = (value: unknown): value is TaskState => value === 'candidate' || value === 'open' || value === 'done' || value === 'cancelled'
const revision = (row: CompanionRow): string | null => typeof row.revision === 'string' && /^[a-f0-9]{64}$/i.test(row.revision) ? row.revision : null
const localDateTime = (at = Date.now() + 60 * 60 * 1000): string => {
  const date = new Date(at - new Date(at).getTimezoneOffset() * 60_000)
  return date.toISOString().slice(0, 16)
}

/** User-facing task ledger backed by the same rows the Agent reads; it never fabricates tasks. */
export function AssistantTasks() {
  const [items, setItems] = useState<CompanionRow[]>([])
  const [filter, setFilter] = useState<TaskState | 'all'>('all')
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [message, setMessage] = useState('')
  const [revisionToken, setRevisionToken] = useState(0)
  const [reminderFor, setReminderFor] = useState<string | null>(null)
  const [reminderAt, setReminderAt] = useState(() => localDateTime())

  useEffect(() => {
    let active = true
    setLoading(true); setMessage('')
    runtimeBridge.companionDashboard({ operation: 'taskList', query: '' }).then(result => {
      if (active) setItems(companionRows(result.items))
    }).catch(() => {
      if (active) setMessage('无法读取事项；不会把读取失败显示成没有事项。')
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [revisionToken])

  const visible = useMemo(() => filter === 'all' ? items : items.filter(item => item.state === filter), [filter, items])
  const update = (row: CompanionRow, state: 'open' | 'done' | 'cancelled'): void => {
    const expectedState = row.state
    const expectedRevision = revision(row)
    if (!validState(expectedState) || expectedRevision === null || busyId !== null) {
      setMessage('事项信息已变化，请刷新后再操作。'); return
    }
    setBusyId(row.id); setMessage('')
    const request: CompanionRequest = { operation: 'taskUpdate', id: row.id, expectedState, expectedRevision, state }
    runtimeBridge.companionDashboard(request).then(result => {
      if (result.ok !== true) throw new Error('not updated')
      setItems(current => current.map(item => item.id === row.id ? { ...item, state } : item))
      setMessage(state === 'open' ? '事项已标记为待处理。' : state === 'done' ? '事项已标记为完成；关联提醒已失效。' : '事项已取消；关联提醒已失效。')
    }).catch(() => setMessage('事项已变化或更新失败，请刷新后重试。'))
      .finally(() => setBusyId(null))
  }
  const schedule = (row: CompanionRow): void => {
    const expectedState = row.state
    const expectedRevision = revision(row)
    const reminderAtMs = new Date(reminderAt).getTime()
    if (!validState(expectedState) || !['candidate', 'open'].includes(expectedState) || expectedRevision === null || !Number.isSafeInteger(reminderAtMs) || reminderAtMs <= Date.now() || busyId !== null) {
      setMessage('请选择未来的提醒时间；事项变化后请刷新再试。'); return
    }
    setBusyId(row.id); setMessage('')
    const request: CompanionRequest = { operation: 'taskUpdate', id: row.id, expectedState, expectedRevision, reminderAtMs }
    runtimeBridge.companionDashboard(request).then(result => {
      if (result.ok !== true) throw new Error('not scheduled')
      setItems(current => current.map(item => item.id === row.id ? { ...item, state: 'open', reminderAtMs } : item))
      setReminderFor(null)
      setMessage('事项已标记为待处理，并已交给系统安排提醒；系统省电可能延迟投递。')
    }).catch(() => setMessage('事项已变化或提醒无法安排；本次提醒状态已撤销，请刷新后重试。'))
      .finally(() => setBusyId(null))
  }

  return <section className="assistant-tasks" aria-labelledby="assistant-tasks-title">
    <header><div><span className="workspace-kicker">YOUR ASSISTANT</span><h2 id="assistant-tasks-title">事项</h2><p>来自通知或资料整理的待办候选。标题和来源可能不准确，请先核对再完成、取消或设置提醒。</p></div>
      <button className="icon-button" type="button" aria-label="刷新事项" disabled={loading || busyId !== null} onClick={() => setRevisionToken(value => value + 1)}><RefreshCw size={18} /></button></header>
    <div className="assistant-task-filters" role="tablist" aria-label="事项状态筛选">{states.map(state => <button key={state.id} type="button" role="tab" aria-selected={filter === state.id} onClick={() => setFilter(state.id)}>{state.label}</button>)}</div>
    {loading && <p role="status">正在读取事项…</p>}
    {message && <p role="status">{message}</p>}
    {!loading && !message.startsWith('无法读取') && visible.length === 0 && <p>当前筛选下没有事项。</p>}
    <div className="assistant-task-list">{visible.map(row => <article key={row.id} className="assistant-task-card"><div><strong>{row.text}</strong><small>{label(row.state)} · {row.sourceKind === 'shared_material' ? '资料整理' : '通知整理'}{typeof row.sourceRecordId === 'string' ? ' · 已关联资料' : ''}{typeof row.reminderAtMs === 'number' ? ` · 提醒 ${new Date(row.reminderAtMs).toLocaleString()}` : ''}</small></div>
      <div className="assistant-task-actions">{row.state === 'candidate' && <button type="button" disabled={busyId !== null} onClick={() => update(row, 'open')}><CircleDot size={16} />标记待处理</button>}{row.state === 'open' && <button type="button" disabled={busyId !== null} onClick={() => update(row, 'done')}><CheckCircle2 size={16} />完成</button>}{(row.state === 'candidate' || row.state === 'open') && <button type="button" disabled={busyId !== null} onClick={() => { setReminderFor(row.id); setReminderAt(localDateTime()) }}>安排提醒</button>}{(row.state === 'candidate' || row.state === 'open') && <button type="button" disabled={busyId !== null} onClick={() => update(row, 'cancelled')}><XCircle size={16} />取消</button>}</div>
      {reminderFor === row.id && <form className="assistant-task-reminder" onSubmit={event => { event.preventDefault(); schedule(row) }}><label>提醒时间<input type="datetime-local" required value={reminderAt} onChange={event => setReminderAt(event.target.value)} /></label><button type="submit" disabled={busyId !== null}>确认安排</button><button type="button" disabled={busyId !== null} onClick={() => setReminderFor(null)}>返回</button></form>}
    </article>)}</div>
  </section>
}
