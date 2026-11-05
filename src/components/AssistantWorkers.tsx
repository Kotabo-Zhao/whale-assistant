import { WorkerDependencyDetail, WorkerTaskDetail } from './WorkerTaskDetail'
import { WorkerGroupReport } from './WorkerGroupReport'
import { useEffect, useRef, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { pollWhileVisible } from '../pollWhileVisible'
import { companionRows, type CompanionRow } from '../platform/companion'
import { SuperagentSettings } from './SuperagentSettings'

const states: Record<string, string> = { unreadable: '记录待检查', queued: '排队中', starting: '启动中', running: '执行中', waiting: '等你回答', blocked: '遇到阻碍', interrupted: '中断待确认', failed: '未完成', cancelling: '正在取消', cancelled: '已取消', completed: '已完成' }
export function AssistantWorkers({ onOpenSession, visible = true }: { onOpenSession: (id: string) => void; visible?: boolean }) {
  const [counts, setCounts] = useState<{ running: number | null; attention: number | null }>({ running: null, attention: null })
  const [rows, setRows] = useState<CompanionRow[]>([])
  const [watch, setWatch] = useState({ enabled: false, active: false })
  const [message, setMessage] = useState('')
  const [addingTo, setAddingTo] = useState<string | null>(null)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [manualOpen, setManualOpen] = useState(false)
  const [task, setTask] = useState('')
  const [mode, setMode] = useState<'read' | 'create' | 'device'>('read')
  const [submitting, setSubmitting] = useState(false)
  const [pending, setPending] = useState<{id: string; task: string; notice: string; held: boolean}[]>([])
  const [group, setGroup] = useState('')
  const [report, setReport] = useState<Record<string, unknown> | null>(null)
  const [offset, setOffset] = useState(0)
  const [total, setTotal] = useState(0)
  const [groupsOpen, setGroupsOpen] = useState(false)
  const [groups, setGroups] = useState<string[]>([])
  const [filter, setFilter] = useState('all')
  const [listState, setListState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const lastQuery = useRef('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    if (!visible) return
    const queryKey = JSON.stringify([filter, group, offset])
    if (lastQuery.current !== queryKey) {
      lastQuery.current = queryKey
      setRows([]); setReport(null); setTotal(0); setListState('loading')
    }
    let active = true
    let inFlight = false
    let changed = false
    const load = async (): Promise<void> => {
      if (!active || document.visibilityState === 'hidden') return
      if (inFlight) { changed = true; return }
      inFlight = true
      try {
        const tasks = await runtimeBridge.companionDashboard({ operation: 'workerPage', filter, group, offset, limit: 20 })
        if (active) {
          const countOf = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0 ? Number(value) : null
          setCounts({ running: countOf(tasks.running), attention: countOf(tasks.attention) })
          const items = companionRows(tasks.items)
          const count = Number.isSafeInteger(tasks.total) && Number(tasks.total) >= 0 ? Number(tasks.total) : items.length
          if (offset > 0 && offset >= count) { setOffset(Math.floor(Math.max(0,count-1)/20)*20); return }
          setRows(items); setTotal(count); setListState('ready')
          setReport(tasks.groupReport && typeof tasks.groupReport === 'object' ? tasks.groupReport as Record<string, unknown> : null)
          const submissions: unknown[] = Array.isArray(tasks.submissions) ? tasks.submissions : []
          setPending(submissions.flatMap(row => row && typeof row === 'object' && 'task' in row && typeof row.task === 'string'
            ? [{id: 'commandId' in row && typeof row.commandId === 'string' ? row.commandId : row.task,
              task: row.task, notice: 'notice' in row && typeof row.notice === 'string' ? row.notice : '', held: 'held' in row && row.held === true}] : []))
          if (groupsOpen) {
            const result = await runtimeBridge.companionDashboard({ operation: 'workerGroups' })
            if (active && Array.isArray(result.groups)) setGroups(result.groups.filter((v): v is string => typeof v === 'string'))
          }
        }
      } catch { if (active) setListState('failed') }
      finally { inFlight = false; if (changed && active) { changed = false; void load() } }
    }
    const refresh = () => { void load() }
    let stopFallback: (() => void) | undefined
    const fallback = () => { if (active) stopFallback = pollWhileVisible(15000,load) }
    const listener = runtimeBridge.addWorkerDashboardListener?.(refresh)
    if (listener) void listener.then(refresh, fallback)
    else fallback()
    const onVisible = () => { if (!stopFallback) refresh() }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      active = false; stopFallback?.(); document.removeEventListener('visibilitychange', onVisible)
      void listener?.then(handle => handle.remove()).catch(() => {})
    }
  }, [revision, visible, filter, group, offset, groupsOpen])
  useEffect(() => {
    if (!visible || !settingsOpen) return
    return pollWhileVisible(15000, async current => {
      try {
        const screen = await runtimeBridge.companionDashboard({ operation: 'screenWatchStatus' })
        if (current()) setWatch({ enabled: screen.enabled === true, active: screen.active === true })
      } catch { if (current()) setMessage('无法读取屏幕陪看状态。') }
    })
  }, [visible, settingsOpen, revision])
  const control = async (row: CompanionRow, action: 'cancel' | 'resume' | 'reply' | 'add'): Promise<void> => {
    setBusy(row.id)
    try {
      await runtimeBridge.companionDashboard({ operation: 'workerControl', id: row.id, action, task: answers[row.id] || '' })
      setAnswers(old => ({ ...old, [row.id]: '' }))
      if (action === 'add') setAddingTo(null)
      setMessage('操作已排队，运行中的 Harness 会处理；状态以任务卡片为准。'); setRevision(value => value + 1)
    } catch { setMessage('未能提交，请刷新后重试。') }
    finally { setBusy(null) }
  }
  const resumeForm = (row: CompanionRow) => row.missingLedger !== true && row.authorizationRevoked !== true &&
    ['waiting','blocked','interrupted','failed'].includes(String(row.state)) && <label>{row.state === 'waiting' ? '你的回答' : '确认现场后的继续说明'}
      <textarea value={answers[row.id] || ''} onChange={event => setAnswers(old => ({ ...old, [row.id]: event.target.value }))} />
      <button type="button" disabled={busy !== null || !answers[row.id]?.trim()} onClick={() => { void control(row, row.state === 'waiting' ? 'reply' : 'resume') }}>提交并继续</button></label>
  const screenControl = async (enabled?: boolean): Promise<void> => {
    try {
      await runtimeBridge.companionDashboard(enabled === undefined ? { operation: 'screenWatchStop' } : { operation: 'screenWatchSet', enabled })
      setRevision(value => value + 1)
    } catch { setMessage('屏幕陪看设置未能保存。') }
  }
  const question = listState === 'ready' && offset === 0 && (filter === 'all' || filter === 'attention')
    ? rows.find(row => row.state === 'waiting' && row.question && !row.authorizationRevoked && !row.missingLedger)
    : undefined
  const renderTask = (row: CompanionRow) => <article key={row.id} className="assistant-task-card worker-card" data-state={String(row.state)}>
      <div><strong>{row.text}</strong><small className="worker-card-status">{states[String(row.state)] || '状态待确认'} · {row.mode === 'create' ? '创作工具' : row.mode === 'device' ? '手机操作' : '读取与整理'}</small>
        {typeof row.group === 'string' && row.group !== '' && <small>所属目标 · {row.group}</small>}
        {row.authorizationRevoked === true && <small>任务授权已撤回</small>}
        {row.authoredDraft && typeof row.authoredDraft === 'object' && 'revision' in row.authoredDraft && typeof row.authoredDraft.revision === 'string' ?
          <button type="button" onClick={() => {
            void runtimeBridge.companionDashboard({ operation: 'workerPreviewDraft', id: row.id, revision: (row.authoredDraft as { revision: string }).revision })
              .catch(() => setMessage('草稿已变化，请刷新任务卡。'))
          }}>预览工具草稿</button> : null}
        {typeof row.progress === 'string' && row.progress && <p>进展：{row.progress}</p>}
        {row.state === 'queued' && typeof row.waitReason === 'string' && row.waitReason && <p>{row.waitReason}</p>}
        {typeof row.question === 'string' && row.question && <p>{row.question}</p>}
        {typeof row.summary === 'string' && row.summary !== '' && <p className="worker-text worker-result-preview" aria-label={row.state === 'completed' ? '结果摘要' : '任务说明'}>{row.summary}</p>}
        {row.hasPermissionSettings === true && row.mode === 'read' && typeof row.writeSeq === 'number' &&
          <button type="button" disabled={busy !== null} onClick={() => {
            setBusy(row.id)
            void runtimeBridge.companionDashboard({ operation: 'workerResumeAuthorizedRead', id: row.id, writeSeq: Number(row.writeSeq) })
              .then(result => { setMessage(String(result.message)); setRevision(v => v + 1) })
              .catch(() => setMessage('工单已变化，请刷新查看')).finally(() => setBusy(null))
          }}>继续原任务</button>}
        {row.hasPermissionSettings === true && <button type="button" onClick={() => {
          void runtimeBridge.companionDashboard({ operation: 'workerBlockedSettings', id: row.id })
            .catch(() => setMessage('任务状态已变化，请刷新查看'))
        }}>权限设置</button>}
        {row.state === 'waiting' && resumeForm(row)}
        {row.recordUnreadable !== true && <WorkerTaskDetail row={row} />}
        <WorkerDependencyDetail target={row.dependencyTarget} action={resumeForm} />
        {typeof row.uiError === 'string' && row.uiError && <p>{row.uiError}</p>}
        {addingTo === row.id && row.missingLedger !== true && row.authorizationRevoked !== true && ['queued', 'starting', 'running', 'completed'].includes(String(row.state)) && <label>追加任务信息（当前轮结束后处理）
          <textarea value={answers[row.id] || ''} onChange={event => setAnswers(old => ({ ...old, [row.id]: event.target.value }))} />
          <button type="button" disabled={busy !== null || !answers[row.id]?.trim()} onClick={() => { void control(row, 'add') }}>提交补充</button></label>}
        {row.state !== 'waiting' && resumeForm(row)}
      </div>
      <div className="assistant-task-actions">
        {row.missingLedger !== true && row.authorizationRevoked !== true && ['queued','starting','running','completed'].includes(String(row.state)) &&
          <button type="button" aria-expanded={addingTo === row.id} onClick={() => setAddingTo(value => value === row.id ? null : row.id)}>
            {addingTo === row.id ? '收起补充' : '追加信息'}</button>}
        <button type="button" disabled={typeof row.sessionId !== 'string'} onClick={() => { if (typeof row.sessionId === 'string') onOpenSession(row.sessionId) }}>查看任务会话</button>
        {row.missingLedger !== true && !['completed', 'failed', 'cancelled', 'cancelling'].includes(String(row.state)) && <button type="button" disabled={busy !== null} onClick={() => { void control(row, 'cancel') }}>取消任务</button>}
      </div>
    </article>
  return <section className="worker-workspace" aria-label="后台协作与屏幕陪看">
    <header className="worker-flow-heading"><div><h3>交给后台，随时回来看看</h3><p>直接在对话中说出需求。这里查看进度、回答追问和领取结果。</p></div></header>
    {question && <div className="assistant-task-list worker-priority-question" aria-label="等待你回答">{renderTask(question)}</div>}
    <div className="worker-overview" role="group" aria-label="全部目标的任务概况">
      {([['running','执行与排队'],['attention','待我处理']] as const).map(([key,label]) => <button type="button" key={key}
        className={`worker-overview-item ${key}`} onClick={() => { setGroup(''); setFilter(key); setOffset(0) }}>
        <span>{label}</span><strong>{listState === 'ready' ? counts[key] ?? '—' : '—'}</strong><small>查看全部目标</small>
      </button>)}
    </div>
    <div className="worker-manual-actions">
      <button type="button" className="button button-secondary" aria-expanded={manualOpen}
        onClick={() => setManualOpen(!manualOpen)}>{manualOpen ? '收起手动交办' : '手动交办'}</button>
    </div>
    {manualOpen && <form className="worker-dispatch" onSubmit={event => {
      event.preventDefault()
      if (!task.trim() || submitting) return
      setSubmitting(true)
      void runtimeBridge.companionDashboard({ operation: 'workerSubmit', task: task.trim(), mode }).then(() => {
        setTask(''); setMode('read'); setManualOpen(false); setRevision(value => value + 1); setMessage('任务已保存。')
      }).catch(() => setMessage('任务未保存，内容已保留。')).finally(() => setSubmitting(false))
    }}>
      <label>交办新任务<textarea rows={2} value={task} maxLength={2000} disabled={submitting}
        placeholder="交给后台做什么？" onChange={event => setTask(event.target.value)} /></label>
      <label>任务类型<select value={mode} disabled={submitting} onChange={event => setMode(event.target.value as typeof mode)}>
        <option value="read">读取整理</option><option value="create">创作工具</option><option value="device">操作设备</option>
      </select></label>
      {mode === 'create' && <small>后台生成独立草稿，完成后可从任务卡预览和启用。</small>}
      <button type="submit" disabled={submitting || !task.trim()}>交办</button>
    </form>}
    {pending.map(row => <div key={row.id} className="worker-card">
      <p>{row.held ? '待授权' : '等待接取'} · {row.task}{row.notice && ' · ' + row.notice}</p>
      {row.held && <div className="assistant-task-actions">{(['settings', 'resume', 'remove'] as const).map((action,i) =>
        <button key={action} type="button" disabled={busy !== null} onClick={() => {
          setBusy(row.id)
          void runtimeBridge.companionDashboard({operation:'workerPermission',id:row.id,action})
            .then(result => { setMessage(String(result.message)); setRevision(v => v + 1) })
            .catch(() => setMessage('未能处理，请重试')).finally(() => setBusy(null))
        }}>{['权限设置','继续原任务','移除'][i]}</button>)}</div>}
    </div>)}
    {message && <p role="status">{message}</p>}
    <div className="worker-catalog-controls">
      <nav className="worker-status-tabs" aria-label="任务状态">
        {([['all','全部任务'],['attention','待我处理'],['running','执行与排队'],['finished','已结束及历史']] as const).map(([key,label]) =>
          <button key={key} type="button" aria-pressed={filter === key} onClick={() => { setFilter(key); setOffset(0) }}>{label}</button>)}
      </nav>
      <button type="button" aria-expanded={groupsOpen} onClick={() => setGroupsOpen(value => !value)}>
        {group ? '所属目标 · '+group : '按目标筛选'}
      </button>
      {groupsOpen && <label>所属目标<select value={group} onChange={event => { setGroup(event.target.value); setOffset(0) }}>
        <option value="">全部目标</option>{groups.map(value => <option key={value}>{value}</option>)}
      </select></label>}
      <button type="button" onClick={() => setRevision(value => value + 1)}>刷新任务</button>
    </div>
    {group && report && listState === 'ready' && <WorkerGroupReport key={group} group={group} report={report} />}
    {listState === 'loading' && <p>正在读取任务…</p>}
    {listState === 'failed' && <p role="status">暂时无法读取任务，请刷新重试。</p>}
    {listState === 'ready' && total === 0 && <p>当前筛选下没有任务。</p>}
    <div className="assistant-task-list">{rows.filter(row => row.id !== question?.id).map(renderTask)}</div>
    {total > 0 && <nav className="worker-page-nav" aria-label="任务分页">
      <span>{offset + 1}–{offset + rows.length} / {total}</span>
      {offset > 0 && <button type="button" onClick={() => setOffset(Math.max(0,offset-20))}>上一页</button>}
      {offset + rows.length < total && <button type="button" onClick={() => setOffset(offset+20)}>下一页</button>}
    </nav>}
    <details className="worker-secondary-settings" onToggle={event => setSettingsOpen(event.currentTarget.open)}><summary>渠道与屏幕陪看设置</summary><SuperagentSettings visible={visible && settingsOpen} />
    <label><input type="checkbox" checked={watch.enabled} onChange={event => { void screenControl(event.target.checked) }} />允许屏幕陪看</label>
    <p>开启后，只有你要求陪看时才采集；关键画面会发给当前视觉模型，每次最多两分钟。{watch.active ? '正在采集。' : '当前未采集。'}</p>
    {watch.active && <button type="button" onClick={() => { void screenControl() }}>立即停止看屏幕</button>}
    </details>
  </section>
}
