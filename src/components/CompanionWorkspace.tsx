import { companionRows, type CompanionRow, type CompanionRequest } from '../platform/companion'
import { useEffect, useRef, useState } from 'react'
import { Bell, BookOpen, Brain, Download, Upload, RefreshCw } from 'lucide-react'
import { runtimeBridge } from '../platform/native'
import './CompanionWorkspace.css'

type Panel = 'reminders' | 'knowledge' | 'memory'
type Row = CompanionRow
type Preview = { token: string; items: Row[]; skipped: number }
type KnowledgeSource = 'shared_text' | 'text_file' | 'original' | 'image_analysis'
type KnowledgeCoverage = 'not_parsed' | 'partial' | 'extracted_text' | 'model_description'
const rows = companionRows
const expected = (row: Row) => typeof row._review === 'string' ? row._review : ''
const originalId = (row: Row) => typeof row.originalId === 'string' && /^[a-f0-9]{64}$/.test(row.originalId) ? row.originalId : null
const formatMinute = (value: number) => `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
const parseMinute = (value: string) => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute }
const memoryCategory = (row: Row) => row.status === 'pending' ? '待确认' : row.source === 'shared_understanding' ? '分享内容' : row.source === 'auto_notification' || row.source === 'auto_notification_group' || row.source === 'user_notification' ? '通知整理' : row.source === 'auto_usage' ? '使用习惯' : row.source === 'auto_understanding' || row.source === 'user_understanding' ? '后台理解' : '手动与对话记忆'
const knowledgeSourceLabel = (source: unknown) => source === 'shared_text' ? '分享文本' : source === 'text_file' ? '文档/文本' : source === 'original' ? '已保存原件' : source === 'image_analysis' ? '图片描述' : '来源未知'
const knowledgeCoverageLabel = (coverage: unknown) => coverage === 'not_parsed' ? '尚未解析' : coverage === 'partial' ? '部分解析' : coverage === 'extracted_text' ? '已提取文字' : coverage === 'model_description' ? '模型图片描述' : '覆盖范围未知'
const days = ['每天', '每周一', '每周二', '每周三', '每周四', '每周五', '每周六', '每周日']

export function CompanionWorkspace({ initialPanel = 'reminders' }: { initialPanel?: Panel }) {
  const [panel, setPanel] = useState<Panel>(initialPanel)
  const [items, setItems] = useState<Row[]>([])
  const [recurring, setRecurring] = useState<Row[]>([])
  const [suggestions, setSuggestions] = useState<Row[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [query, setQuery] = useState('')
  const [text, setText] = useState('')
  const [date, setDate] = useState('')
  const [time, setTime] = useState('09:00')
  const [weekday, setWeekday] = useState('0')
  const [repeat, setRepeat] = useState(false)
  const [quietStart, setQuietStart] = useState('22:00')
  const [quietEnd, setQuietEnd] = useState('08:00')
  const [notificationStatus, setNotificationStatus] = useState('')
  const [editing, setEditing] = useState<Row | null>(null)
  const [review, setReview] = useState<{ snapshot: string; candidates: Row[]; action: 'add' | 'edit' | 'approve'; row?: Row } | null>(null)
  const [preview, setPreview] = useState<Preview | null>(null)
  const [revision, setRevision] = useState(0)
  const [revealedMemory, setRevealedMemory] = useState<string | null>(null)
  const swipeStart = useRef<{ x: number; y: number; id: string } | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadFailed, setLoadFailed] = useState(false)
  const [knowledgeNextOffset, setKnowledgeNextOffset] = useState<number | null>(null)
  const [loadingMoreKnowledge, setLoadingMoreKnowledge] = useState(false)
  const [memoryNextBefore, setMemoryNextBefore] = useState<number | null>(null)
  const [memoryRevision, setMemoryRevision] = useState<number | null>(null)
  const [loadingMoreMemory, setLoadingMoreMemory] = useState(false)
  const [undoKnowledge, setUndoKnowledge] = useState<Row | null>(null)
  const [memorySource, setMemorySource] = useState('all')
  const [memoryOrder, setMemoryOrder] = useState<'recent' | 'oldest' | 'source' | 'type'>('recent')
  const [knowledgeSource, setKnowledgeSource] = useState<KnowledgeSource | 'all'>('all')
  const [knowledgeCoverage, setKnowledgeCoverage] = useState<KnowledgeCoverage | 'all'>('all')
  const pending = useRef(false)
  const previousPanel = useRef(panel)
  const knowledgeQuery = panel === 'knowledge' ? query.trim() : ''
  useEffect(() => {
    let live = true
    if (previousPanel.current !== panel) {
      setItems([]); setRecurring([]); setSuggestions([]); setPreview(null); setReview(null); setEditing(null); setText(''); setMessage(''); setMemorySource('all'); setMemoryOrder('recent'); setKnowledgeSource('all'); setKnowledgeCoverage('all')
      previousPanel.current = panel
    }
    setLoading(true); setLoadFailed(false)
    const request: CompanionRequest = panel === 'reminders'
      ? { operation: 'reminderList', query: '' }
      : panel === 'memory'
        ? { operation: 'memoryList', query: '' }
        : { operation: 'knowledgeList', query: knowledgeQuery, offset: 0, ...(knowledgeSource === 'all' ? {} : { source: knowledgeSource }), ...(knowledgeCoverage === 'all' ? {} : { coverage: knowledgeCoverage }) }
    void Promise.resolve().then(() => runtimeBridge.companionDashboard(request)).then(result => {
      const nextItems = rows(result.items); const nextRecurring = rows(result.recurring ?? []); const nextSuggestions = rows(result.suggestions ?? [])
      if (live) {
        setItems(nextItems); setRecurring(nextRecurring); setSuggestions(nextSuggestions)
        setKnowledgeNextOffset(panel === 'knowledge' && Number.isSafeInteger(result.nextOffset) && (result.nextOffset as number) >= 0 ? result.nextOffset as number : null)
        setMemoryNextBefore(panel === 'memory' && Number.isSafeInteger(result.nextBefore) && (result.nextBefore as number) >= 0 ? result.nextBefore as number : null)
        setMemoryRevision(panel === 'memory' && Number.isSafeInteger(result.revision) && (result.revision as number) >= 0 ? result.revision as number : null)
        if (typeof result.quietStart === 'number') setQuietStart(formatMinute(result.quietStart)); if (typeof result.quietEnd === 'number') setQuietEnd(formatMinute(result.quietEnd)); setNotificationStatus(typeof result.notificationStatus === 'string' ? result.notificationStatus : '')
      }
    }).catch(() => { if (live) { setLoadFailed(true); setMessage('无法读取数据，原有内容已保留。') } })
    .finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [panel, revision, knowledgeQuery, knowledgeSource, knowledgeCoverage])
  const run = async (request: CompanionRequest, success: string) => {
    if (pending.current) return null
    pending.current = true
    setBusy(true); setMessage('')
    try { const result = await runtimeBridge.companionDashboard(request); if (result.result === 'STALE') { setMessage('记忆已变化，请重新核对。'); return null } if (result.ok === false || result.result === 'EXISTING_PENDING' || result.result === 'EXISTING_CONFIRMED') { setMessage('相同内容已存在或等待确认，未重复保存。'); return null } setText(''); setEditing(null); setReview(null); setPreview(null); setRevision(v => v + 1); setMessage(success); return result }
    catch { setMessage('操作未完成，数据可能已变化或输入不符合要求；请刷新后重试。'); return null }
    finally { pending.current = false; setBusy(false) }
  }
  const deleteKnowledge = async (row: Row) => {
    if (!window.confirm('删除这条资料？十分钟内可撤销；关联原件和已抑制的派生记忆不会因此恢复。')) return
    const result = await run({ operation: 'knowledgeDelete', id: row.id, expected: expected(row), expectedText: row.text }, '资料已删除，可在十分钟内撤销。')
    if (result) setUndoKnowledge(row)
  }
  const deleteKnowledgeOriginal = async (row: Row) => {
    const verifiedOriginalId = originalId(row)
    if (!verifiedOriginalId) { setMessage('这条资料没有可验证的本地原件，不能执行整份删除。'); return }
    if (!window.confirm('永久删除这份原件及其全部本地资料分段？将同时抑制关联派生记忆，不能撤销。外部副本和此前已发送给模型的内容不会被删除。')) return
    const result = await run({ operation: 'knowledgeRemoveOriginal', id: row.id, originalId: verifiedOriginalId, expectedText: row.text }, '')
    if (!result) return
    setUndoKnowledge(null)
    const records = typeof result.records === 'number' ? result.records : 0
    setMessage(result.objectDeleted === true
      ? `已永久删除原件及 ${records} 条本地资料，不能撤销。`
      : `已永久删除 ${records} 条本地资料；原件对象不可用或未删除，不能撤销。`)
  }
  const undoKnowledgeDelete = async () => {
    if (undoKnowledge === null) return
    const result = await run({ operation: 'knowledgeUndoDelete', id: undoKnowledge.id }, '资料已恢复；此前已抑制的派生记忆不会自动恢复。')
    if (result) setUndoKnowledge(null)
  }
  const startMemoryReview = async (action: 'add' | 'edit' | 'approve', row?: Row) => {
    const value = action === 'approve' ? row?.text ?? '' : text.trim()
    if (!value) return
    setBusy(true); setMessage('')
    try {
      const result = await runtimeBridge.companionDashboard({ operation: 'memoryReview', text: value, ...(row ? { id: row.id } : {}) })
      if (typeof result.snapshot !== 'string' || !Array.isArray(result.candidates)) throw new Error('invalid')
      setReview({ snapshot: result.snapshot, candidates: result.candidates as Row[], action, row })
    } catch { setMessage('无法核对相关记忆，原数据已保留。') } finally { setBusy(false) }
  }
  const saveMemory = async () => {
    if (!review) return
    const row = review.row
    const request: CompanionRequest = review.action === 'add'
      ? { operation: 'memoryAdd', text: text.trim(), snapshot: review.snapshot }
      : { operation: review.action === 'approve' ? 'memoryApprove' : row?.status === 'pending' ? 'memoryApproveEdit' : 'memoryEdit', id: row!.id, text: text.trim(), snapshot: review.snapshot, expectedText: row!.text, expected: expected(row!) }
    const result = await run(request, '记忆已处理。')
    if (!result) setReview(null)
  }
  const readFile = async (file: File, kind: 'memory' | 'knowledge' | 'text') => {
    if (file.size > (kind === 'memory' ? 256000 : kind === 'text' ? 32000 : 512000)) { setMessage('文件超过大小上限。'); return }
    setBusy(true); setMessage('')
    try {
      const contents = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
      if (kind === 'text') { setText(contents); setEditing(null); return }
      const result = await runtimeBridge.companionDashboard({ operation: 'previewImport', kind, contents })
      if (typeof result.token !== 'string' || !Array.isArray(result.items) || typeof result.skipped !== 'number') throw new Error('invalid')
      setPreview({ token: result.token, items: rows(result.items), skipped: result.skipped })
    } catch { setMessage('文件无法读取或格式不正确，原数据已保留。') } finally { setBusy(false) }
  }
  const exportFile = async (kind: 'memory' | 'knowledge') => {
    setBusy(true); setMessage('')
    try { await runtimeBridge.exportCompanionBackup(kind); setMessage('备份已保存。') }
    catch { setMessage('未完成导出，原数据不受影响。') } finally { setBusy(false) }
  }
  const loadMoreKnowledge = async () => {
    if (busy || loadingMoreKnowledge || panel !== 'knowledge' || knowledgeNextOffset === null) return
    setLoadingMoreKnowledge(true); setMessage('')
    try {
      const result = await runtimeBridge.companionDashboard({ operation: 'knowledgeList', query: knowledgeQuery, offset: knowledgeNextOffset, ...(knowledgeSource === 'all' ? {} : { source: knowledgeSource }), ...(knowledgeCoverage === 'all' ? {} : { coverage: knowledgeCoverage }) })
      const next = rows(result.items)
      setItems(current => {
        const merged = new Map(current.map(row => [row.id, row])); next.forEach(row => merged.set(row.id, row)); return [...merged.values()]
      })
      setKnowledgeNextOffset(Number.isSafeInteger(result.nextOffset) && (result.nextOffset as number) >= 0 ? result.nextOffset as number : null)
    } catch { setMessage('无法加载更多资料，已显示的内容不受影响。') }
    finally { setLoadingMoreKnowledge(false) }
  }
  const loadMoreMemory = async () => {
    if (busy || loadingMoreMemory || panel !== 'memory' || memoryNextBefore === null || memoryRevision === null) return
    setLoadingMoreMemory(true); setMessage('')
    try {
      const result = await runtimeBridge.companionDashboard({ operation: 'memoryPage', before: memoryNextBefore, revision: memoryRevision })
      const next = rows(result.items)
      setItems(current => { const merged = new Map(current.map(row => [row.id, row])); next.forEach(row => merged.set(row.id, row)); return [...merged.values()] })
      setMemoryNextBefore(Number.isSafeInteger(result.nextBefore) && (result.nextBefore as number) >= 0 ? result.nextBefore as number : null)
      setMemoryRevision(Number.isSafeInteger(result.revision) && (result.revision as number) >= 0 ? result.revision as number : null)
    } catch { setMessage('记忆已变化或无法加载更多，请重新读取。') }
    finally { setLoadingMoreMemory(false) }
  }
  const list = panel === 'knowledge' ? items : items.filter(row => row.text.toLowerCase().includes(query.trim().toLowerCase()))
  const memorySources = [...new Set(items.map(row => row.source).filter((source): source is string => typeof source === 'string' && source !== ''))].sort()
  const memoryList = panel !== 'memory' ? list : [...list]
    .filter(row => memorySource === 'all' || row.source === memorySource)
    .sort((left, right) => {
      if (memoryOrder === 'source') return String(left.source ?? '').localeCompare(String(right.source ?? '')) || Number(right.createdAt ?? 0) - Number(left.createdAt ?? 0)
      if (memoryOrder === 'type') return memoryCategory(left).localeCompare(memoryCategory(right)) || Number(right.createdAt ?? 0) - Number(left.createdAt ?? 0)
      const direction = memoryOrder === 'oldest' ? 1 : -1
      return direction * (Number(left.createdAt ?? 0) - Number(right.createdAt ?? 0))
    })
  const groups = [...new Set(memoryList.map(memoryCategory))].map(name => ({ name, rows: memoryList.filter(row => memoryCategory(row) === name) }))
  return <section className="companion-workspace" aria-labelledby="companion-workspace-title">
    <div className="workspace-heading"><div><span className="workspace-kicker">YOUR ASSISTANT</span><h3 id="companion-workspace-title">助手工作台</h3><p>提醒、资料与长期记忆集中管理，数据保存在手机本地。</p></div>
      <button className="icon-button" type="button" aria-label="刷新助手数据" disabled={busy} onClick={() => setRevision(v => v + 1)}><RefreshCw size={18} /></button></div>
    <div className="workspace-tabs" role="tablist" aria-label="助手工作台分类">
      {([{ id: 'reminders', label: '提醒', icon: <Bell size={17} /> }, { id: 'knowledge', label: '资料', icon: <BookOpen size={17} /> }, { id: 'memory', label: '长期记忆', icon: <Brain size={17} /> }] as const).map(tab =>
        <button key={tab.id} id={`companion-workspace-tab-${tab.id}`} role="tab" aria-selected={panel === tab.id} aria-controls={`companion-workspace-panel-${tab.id}`} tabIndex={panel === tab.id ? 0 : -1} type="button" disabled={busy} onClick={() => { setQuery(''); setPanel(tab.id) }}>{tab.icon}{tab.label}</button>)}
    </div>
    {message && <p className="workspace-message" role="status" aria-live="polite">{message}</p>}
    {loading && <p className="workspace-hint" role="status">正在读取本地数据…</p>}
    {loadFailed && <button className="button button-secondary" onClick={() => setRevision(v => v + 1)}>重新读取数据</button>}
    {!loading && !loadFailed && items.length === 0 && recurring.length === 0 && <p className="workspace-hint">当前分类还没有内容，可以在下方添加或导入。</p>}
    <fieldset id={`companion-workspace-panel-${panel}`} className="workspace-fields" role="tabpanel" aria-labelledby={`companion-workspace-tab-${panel}`} aria-busy={busy || loading} disabled={busy}>
    {panel === 'reminders' ? <>
      <p className="workspace-hint">提醒需要系统通知权限；省电和后台限制可能延迟送达。{notificationStatus && (notificationStatus === 'READY' ? '通知权限已就绪。' : '当前通知状态未就绪，请检查系统通知设置。')}</p>
      <div className="workspace-form-row"><label>免打扰开始<input type="time" value={quietStart} onChange={e => setQuietStart(e.target.value)} /></label><label>免打扰结束<input type="time" value={quietEnd} onChange={e => setQuietEnd(e.target.value)} /></label></div>
      <button type="button" className="button button-secondary" disabled={busy || !quietStart || !quietEnd} onClick={() => { void run({ operation: 'quietHoursSet', start: parseMinute(quietStart), end: parseMinute(quietEnd) }, '免打扰时段已保存。') }}>保存免打扰时段</button>
      <label>提醒内容<input value={text} maxLength={200} onChange={e => setText(e.target.value)} placeholder="例如：下午取快递" /></label>
      <label className="workspace-check"><input type="checkbox" checked={repeat} onChange={e => setRepeat(e.target.checked)} />重复提醒</label>
      <div className="workspace-form-row">{repeat ? <label>重复周期<select value={weekday} onChange={e => setWeekday(e.target.value)}>{days.map((day, index) => <option key={day} value={index}>{day}</option>)}</select></label> : <label>日期<input type="date" value={date} onChange={e => setDate(e.target.value)} /></label>}
        <label>时间<input type="time" value={time} onChange={e => setTime(e.target.value)} /></label></div>
      <button className="button button-primary" type="button" disabled={busy || !text.trim() || (!repeat && !date)} onClick={() => {
        const [hour, minute] = time.split(':').map(Number)
        const schedule = { text: text.trim(), hour, minute, weekday: Number(weekday) }
        const request: CompanionRequest = repeat ? (editing ? { operation: 'recurringEdit', id: editing.id, expected: expected(editing), ...schedule } : { operation: 'recurringAdd', ...schedule }) : { operation: 'reminderAdd', text: text.trim(), at: new Date(`${date}T${time}`).getTime() }
        void run(request, '提醒已安排。')
      }}>{editing ? '保存重复提醒' : '添加提醒'}</button>{editing && <button type="button" onClick={() => { setEditing(null); setText('') }}>取消编辑</button>}
      <div className="workspace-list">{[...list, ...recurring].map(row => <article className="workspace-card" key={row.id}><div><strong>{row.text}</strong><small>{row.nextAt ? `${days[row.weekday ?? 0]} · ${String(row.hour).padStart(2, '0')}:${String(row.minute).padStart(2, '0')} · 下次 ${new Date(row.nextAt).toLocaleString()}` : row.at ? new Date(row.at).toLocaleString() : ''} · {row.state}</small></div><div className="workspace-card-actions">
        {row.nextAt && <button type="button" onClick={() => { setEditing(row); setText(row.text); setRepeat(true); setWeekday(String(row.weekday ?? 0)); setTime(`${String(row.hour ?? 0).padStart(2, '0')}:${String(row.minute ?? 0).padStart(2, '0')}`) }}>编辑</button>}
        {row.nextAt && row.state === 'failed' && <button type="button" onClick={() => { void run({ operation: 'recurringRetry', id: row.id }, '已重新安排。') }}>重试</button>}
        <button type="button" onClick={() => { if (window.confirm(`删除提醒“${row.text}”？`)) void run({ operation: row.nextAt ? 'recurringDelete' : 'reminderDelete', id: row.id, expected: expected(row) }, '提醒已删除。') }}>删除</button></div></article>)}</div>
    </> : <>
      <div className="workspace-backup"><button type="button" className="button button-secondary" disabled={busy} onClick={() => { void exportFile(panel) }}><Download size={17} />导出备份</button>
        <label className="button button-secondary"><Upload size={17} />导入备份<input type="file" accept="application/json,.json" disabled={busy} onChange={e => { const file = e.target.files?.[0]; if (file) void readFile(file, panel); e.target.value = '' }} /></label>
        {panel === 'knowledge' && <label className="button button-secondary"><Upload size={17} />导入文本<input type="file" accept="text/plain,.txt" disabled={busy} onChange={e => { const file = e.target.files?.[0]; if (file) void readFile(file, 'text'); e.target.value = '' }} /></label>}</div>
      <p className="workspace-hint">备份为未加密 JSON；导入只合并新增内容，确认前不会写入。</p>
      {preview && <div className="workspace-review"><strong>核对导入内容</strong><p>新增 {preview.items.length} 条，跳过 {preview.skipped} 条已有内容。</p>{preview.items.map(row => <p key={row.id}>{row.text}</p>)}<div className="workspace-card-actions"><button type="button" onClick={() => setPreview(null)}>取消</button><button type="button" disabled={busy} onClick={() => { void run({ operation: 'applyImport', token: preview.token }, '已合并备份。') }}>确认合并</button></div></div>}
      {panel === 'memory' && <details className="workspace-review"><summary><strong>通知中的记忆候选（{suggestions.length}）</strong></summary><p>候选来自通知，可能由他人发送或已经过时；只有你核对并保存后才会成为记忆。</p>
        {suggestions.length === 0 && <p>暂无候选；可在上方开启从通知中寻找候选。</p>}
        {suggestions.map(row => <article className="workspace-card" key={row.id}><div><strong>{row.text}</strong><small>{row.source} · {typeof row.time === 'number' ? new Date(row.time).toLocaleString() : ''}</small></div><div className="workspace-card-actions"><button type="button" onClick={() => { setEditing(null); setText(row.text) }}>整理成记忆</button><button type="button" onClick={() => { void run({ operation: 'memorySuggestionDismiss', id: row.id }, '已忽略候选。') }}>忽略</button></div></article>)}
        <button type="button" className="button button-secondary" onClick={() => { void run({ operation: 'memorySuggestionsReset' }, '已重置忽略记录。') }}>重置已忽略候选</button>
      </details>}
      <label>搜索<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder={panel === 'memory' ? '搜索已确认及待确认的记忆' : '搜索本地资料'} /></label>
      {panel === 'knowledge' && <div className="workspace-form-row knowledge-filter-row">
        <label>资料来源<select value={knowledgeSource} onChange={event => setKnowledgeSource(event.target.value as typeof knowledgeSource)}><option value="all">全部来源</option><option value="shared_text">分享文本</option><option value="text_file">文档/文本</option><option value="original">已保存原件</option><option value="image_analysis">图片描述</option></select></label>
        <label>解析覆盖<select value={knowledgeCoverage} onChange={event => setKnowledgeCoverage(event.target.value as typeof knowledgeCoverage)}><option value="all">全部状态</option><option value="not_parsed">尚未解析</option><option value="partial">部分解析</option><option value="extracted_text">已提取文字</option><option value="model_description">模型图片描述</option></select></label>
      </div>}
      {panel === 'memory' && <div className="workspace-form-row memory-filter-row">
        <label>来源筛选<select value={memorySource} onChange={event => setMemorySource(event.target.value)}><option value="all">全部来源</option>{memorySources.map(source => <option key={source} value={source}>{source}</option>)}</select></label>
        <label>排序<select value={memoryOrder} onChange={event => setMemoryOrder(event.target.value as typeof memoryOrder)}><option value="recent">最近</option><option value="oldest">最早</option><option value="source">来源</option><option value="type">类型</option></select></label>
      </div>}
      <label>{panel === 'memory' ? '记忆内容' : '资料内容'}<textarea value={text} maxLength={panel === 'memory' ? 600 : 8000} rows={3} onChange={e => setText(e.target.value)} placeholder={panel === 'memory' ? '写下希望助手记住的事实或偏好' : '输入或导入需要保存的资料'} /></label>
      <div className="workspace-card-actions"><button className="button button-primary" type="button" disabled={busy || !text.trim()} onClick={() => {
        if (panel === 'memory') { void startMemoryReview(editing ? 'edit' : 'add', editing ?? undefined); return }
        void run(editing ? { operation: 'knowledgeEdit', id: editing.id, expectedText: editing.text, text: text.trim() } : { operation: 'knowledgeAdd', text: text.trim() }, '资料已处理。')
      }}>{editing ? '保存修改' : '添加'}</button>{editing && <button type="button" onClick={() => { setEditing(null); setText('') }}>取消编辑</button>}</div>
      {review && <div className="workspace-review"><strong>核对相关记忆</strong><p>相关内容仅供参考，请确认是否重复、冲突或包含敏感信息。</p>{review.candidates.map((row, i) => <p key={i}>{row.status}：{row.text}</p>)}<div className="workspace-card-actions"><button type="button" onClick={() => setReview(null)}>取消</button><button type="button" disabled={busy} onClick={() => { void saveMemory() }}>确认保存</button></div></div>}
      {panel === 'memory' && memoryList.length > 0 && <p className="workspace-hint">展开分类查看缩略卡片，点击卡片查看详情；左滑可删除。</p>}
      {panel === 'memory' ? <><div className="memory-groups">{groups.map(group => <details className="memory-group" key={group.name} open={query.trim() ? true : undefined}>
        <summary><span>{group.name}</span><small>{group.rows.length} 条</small></summary>
        <div className="workspace-list">{group.rows.map(row => <article className="workspace-card workspace-memory-card" key={row.id}
          onTouchStart={event => { if (event.touches.length === 1) swipeStart.current = { x: event.touches[0].clientX, y: event.touches[0].clientY, id: row.id } }}
          onTouchEnd={event => { const start = swipeStart.current; swipeStart.current = null; if (!start || start.id !== row.id || !event.changedTouches.length) return; const dx = event.changedTouches[0].clientX - start.x; const dy = event.changedTouches[0].clientY - start.y; if (Math.abs(dx) > 48 && Math.abs(dx) > Math.abs(dy)) setRevealedMemory(dx < 0 ? row.id : null) }}>
          <details className="memory-card-detail"><summary><span className="memory-preview">{row.text}</span><small>{row.status === 'pending' ? '待确认' : row.status === 'unverified' || (row.source === 'shared_understanding' && !row.confirmedAt) ? '待核实' : '已保存'}</small></summary>
            <p>{row.text}</p><small>来源：{row.source ?? '未知'}</small>
            <div className="workspace-card-actions">
              {row.status === 'pending' && <button type="button" onClick={() => { void startMemoryReview('approve', row) }}>确认</button>}
              <button type="button" onClick={() => { setEditing(row); setText(row.text) }}>编辑</button>
              <button type="button" onClick={() => setRevealedMemory(revealedMemory === row.id ? null : row.id)}>操作</button>
            </div>
          </details>
          {revealedMemory === row.id && <button type="button" className="memory-delete" onClick={() => { if (window.confirm('删除这条记忆？')) void run({ operation: 'memoryDelete', id: row.id, expected: expected(row), expectedText: row.text }, '已删除。') }}>删除</button>}
        </article>)}</div>
      </details>)}</div>{memoryNextBefore !== null && query.trim() === '' && <button className="button button-secondary" type="button" disabled={busy || loadingMoreMemory} onClick={() => { void loadMoreMemory() }}>{loadingMoreMemory ? '正在加载记忆…' : '加载更多记忆'}</button>}</> : <><div className="workspace-list">{list.map(row => <article className="workspace-card" key={row.id}><div><strong>{typeof row.displayName === 'string' && row.displayName !== '' ? row.displayName : row.text}</strong><small>{knowledgeSourceLabel(row.source)} · {knowledgeCoverageLabel(row.coverage)}{typeof row.displayName === 'string' && row.displayName !== '' ? ` · ${row.text.slice(0, 120)}` : ''}</small></div><div className="workspace-card-actions">
        <button type="button" onClick={() => { setEditing(row); setText(row.text) }}>编辑</button>
        <button type="button" onClick={() => { void deleteKnowledge(row) }}>删除</button>
        {originalId(row) !== null && <button type="button" className="button-danger-quiet" onClick={() => { void deleteKnowledgeOriginal(row) }}>永久删除整份原件</button>}
      </div></article>)}</div>{knowledgeNextOffset !== null && <button className="button button-secondary" type="button" disabled={busy || loadingMoreKnowledge} onClick={() => { void loadMoreKnowledge() }}>{loadingMoreKnowledge ? '正在加载资料…' : '加载更多资料'}</button>}</>}
      {panel === 'knowledge' && undoKnowledge !== null && <p className="workspace-hint" role="status">资料已删除，可在十分钟内撤销。 <button className="button button-quiet" type="button" disabled={busy} onClick={() => { void undoKnowledgeDelete() }}>撤销删除</button></p>}
      {panel === 'memory' && <div className="workspace-hint"><p>删除自动通知或使用习惯记忆后，对应来源会暂停自动整理，避免下次采集立即重新生成。恢复只影响已开启的自动整理功能。</p><button type="button" className="button button-secondary" onClick={() => { if (window.confirm('恢复已删除来源的自动整理？新通知和使用统计可能再次形成记忆。')) void run({ operation: 'memoryResumeAutomatic' }, '自动整理来源已恢复。') }}>恢复已删除来源的自动整理</button></div>}
      {panel === 'memory' && items.length > 0 && <button className="button button-danger-quiet" type="button" onClick={() => { if (window.confirm('清空全部长期记忆并暂停通知和使用习惯的自动整理？此操作无法撤销。')) void run({ operation: 'memoryClear', confirmed: true }, '长期记忆已清空。') }}>清空全部长期记忆</button>}
    </>}
    </fieldset>
  </section>
}
