import { useEffect, useState } from 'react'
import { Bell, BookOpen, Brain, Download, Upload, RefreshCw } from 'lucide-react'
import { runtimeBridge } from '../platform/native'
import './CompanionWorkspace.css'

type Panel = 'reminders' | 'knowledge' | 'memory'
type Row = { id: string; text: string; status?: string; state?: string; source?: string; at?: number; nextAt?: number; hour?: number; minute?: number; weekday?: number; [key: string]: unknown }
type Preview = { token: string; items: Row[]; skipped: number }
const rows = (v: unknown): Row[] => Array.isArray(v) ? (v as unknown[]).filter((row): row is Row => typeof row === 'object' && row !== null && 'id' in row && typeof row.id === 'string' && 'text' in row && typeof row.text === 'string') : []
const expected = (row: Row) => typeof row._review === 'string' ? row._review : ''
const formatMinute = (value: number) => `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
const parseMinute = (value: string) => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute }
const days = ['每天', '每周一', '每周二', '每周三', '每周四', '每周五', '每周六', '每周日']

export function CompanionWorkspace() {
  const [panel, setPanel] = useState<Panel>('reminders')
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
  useEffect(() => {
    let live = true
    setItems([]); setRecurring([]); setSuggestions([]); setPreview(null); setReview(null); setEditing(null); setText('')
    const operation = panel === 'reminders' ? 'reminderList' : panel === 'memory' ? 'memoryList' : 'knowledgeList'
    void Promise.resolve().then(() => runtimeBridge.companionDashboard({ operation, query: '' })).then(result => {
      if (live) { setItems(rows(result.items)); setRecurring(rows(result.recurring)); setSuggestions(rows(result.suggestions)); if (typeof result.quietStart === 'number') setQuietStart(formatMinute(result.quietStart)); if (typeof result.quietEnd === 'number') setQuietEnd(formatMinute(result.quietEnd)); setNotificationStatus(typeof result.notificationStatus === 'string' ? result.notificationStatus : '') }
    }).catch(() => { if (live) setMessage('无法读取数据，原有内容已保留。') })
    return () => { live = false }
  }, [panel, revision])
  const run = async (request: Record<string, unknown>, success: string) => {
    setBusy(true); setMessage('')
    try { const result = await runtimeBridge.companionDashboard(request); if (result.result === 'STALE') { setMessage('记忆已变化，请重新核对。'); return null } if (result.ok === false || result.result === 'EXISTING_PENDING' || result.result === 'EXISTING_CONFIRMED') { setMessage('相同内容已存在或等待确认，未重复保存。'); return null } setRevision(v => v + 1); setMessage(success); return result }
    catch { setMessage('操作未完成，数据可能已变化或输入不符合要求；请刷新后重试。'); return null }
    finally { setBusy(false) }
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
    const operation = review.action === 'add' ? 'memoryAdd' : review.action === 'approve' ? 'memoryApprove' : row?.status === 'pending' ? 'memoryApproveEdit' : 'memoryEdit'
    const result = await run({ operation, text: text.trim(), snapshot: review.snapshot, ...(row ? { id: row.id, expectedText: row.text, expected: expected(row) } : {}) }, '记忆已处理。')
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
  const list = items.filter(row => row.text.toLowerCase().includes(query.trim().toLowerCase()))
  return <section className="companion-workspace" aria-labelledby="companion-workspace-title">
    <div className="workspace-heading"><div><span className="workspace-kicker">YOUR ASSISTANT</span><h3 id="companion-workspace-title">助手工作台</h3><p>提醒、资料与长期记忆集中管理，数据保存在手机本地。</p></div>
      <button className="icon-button" type="button" aria-label="刷新助手数据" disabled={busy} onClick={() => setRevision(v => v + 1)}><RefreshCw size={18} /></button></div>
    <div className="workspace-tabs" role="tablist" aria-label="助手工作台分类">
      {([{ id: 'reminders', label: '提醒', icon: <Bell size={17} /> }, { id: 'knowledge', label: '资料', icon: <BookOpen size={17} /> }, { id: 'memory', label: '长期记忆', icon: <Brain size={17} /> }] as const).map(tab =>
        <button key={tab.id} role="tab" aria-selected={panel === tab.id} type="button" onClick={() => { setQuery(''); setPanel(tab.id) }}>{tab.icon}{tab.label}</button>)}
    </div>
    {message && <p className="workspace-message" role="status">{message}</p>}
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
        const request = repeat ? { operation: editing ? 'recurringEdit' : 'recurringAdd', ...(editing ? { id: editing.id, expected: expected(editing) } : {}), text: text.trim(), hour, minute, weekday: Number(weekday) } : { operation: 'reminderAdd', text: text.trim(), at: new Date(`${date}T${time}`).getTime() }
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
      {panel === 'memory' && <div className="workspace-review"><strong>通知中的记忆候选</strong><p>候选来自通知，可能由他人发送或已经过时；只有你核对并保存后才会成为记忆。</p>
        {suggestions.length === 0 && <p>暂无候选；可在上方开启从通知中寻找候选。</p>}
        {suggestions.map(row => <article className="workspace-card" key={row.id}><div><strong>{row.text}</strong><small>{row.source} · {typeof row.time === 'number' ? new Date(row.time).toLocaleString() : ''}</small></div><div className="workspace-card-actions"><button type="button" onClick={() => { setEditing(null); setText(row.text) }}>整理成记忆</button><button type="button" onClick={() => { void run({ operation: 'memorySuggestionDismiss', id: row.id }, '已忽略候选。') }}>忽略</button></div></article>)}
        <button type="button" className="button button-secondary" onClick={() => { void run({ operation: 'memorySuggestionsReset' }, '已重置忽略记录。') }}>重置已忽略候选</button>
      </div>}
      <label>搜索<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder={panel === 'memory' ? '搜索已确认及待确认的记忆' : '搜索本地资料'} /></label>
      <label>{panel === 'memory' ? '记忆内容' : '资料内容'}<textarea value={text} maxLength={panel === 'memory' ? 600 : 8000} rows={3} onChange={e => setText(e.target.value)} placeholder={panel === 'memory' ? '写下希望助手记住的事实或偏好' : '输入或导入需要保存的资料'} /></label>
      <div className="workspace-card-actions"><button className="button button-primary" type="button" disabled={busy || !text.trim()} onClick={() => {
        if (panel === 'memory') { void startMemoryReview(editing ? 'edit' : 'add', editing ?? undefined); return }
        void run(editing ? { operation: 'knowledgeEdit', id: editing.id, expectedText: editing.text, text: text.trim() } : { operation: 'knowledgeAdd', text: text.trim() }, '资料已处理。')
      }}>{editing ? '保存修改' : '添加'}</button>{editing && <button type="button" onClick={() => { setEditing(null); setText('') }}>取消编辑</button>}</div>
      {review && <div className="workspace-review"><strong>核对相关记忆</strong><p>相关内容仅供参考，请确认是否重复、冲突或包含敏感信息。</p>{review.candidates.map((row, i) => <p key={i}>{row.status}：{row.text}</p>)}<div className="workspace-card-actions"><button type="button" onClick={() => setReview(null)}>取消</button><button type="button" disabled={busy} onClick={() => { void saveMemory() }}>确认保存</button></div></div>}
      <div className="workspace-list">{list.map(row => <article className="workspace-card" key={row.id}><div><strong>{row.text}</strong><small>{panel === 'memory' ? (row.status === 'pending' ? '待确认' : row.source === 'auto_notification' || row.source === 'auto_notification_group' || row.source === 'auto_usage' ? '自动记录' : '已确认') : '本地资料'} · {row.source === 'auto_notification' || row.source === 'auto_notification_group' ? '自动整理的通知，未经核实' : row.source === 'auto_usage' ? '自动整理的使用统计' : row.source ?? '来源未知'}</small></div><div className="workspace-card-actions">
        {panel === 'memory' && row.status === 'pending' && <button type="button" onClick={() => { void startMemoryReview('approve', row) }}>确认</button>}
        <button type="button" onClick={() => { setEditing(row); setText(row.text) }}>编辑</button>
        <button type="button" onClick={() => { if (window.confirm(`删除这条${panel === 'memory' ? '记忆' : '资料'}？`)) void run({ operation: panel === 'memory' ? 'memoryDelete' : 'knowledgeDelete', id: row.id, expected: expected(row), expectedText: row.text }, '已删除。') }}>删除</button></div></article>)}</div>
      {panel === 'memory' && items.length > 0 && <button className="button button-danger-quiet" type="button" onClick={() => { if (window.confirm('清空全部长期记忆？此操作无法撤销。')) void run({ operation: 'memoryClear', confirmed: true }, '长期记忆已清空。') }}>清空全部长期记忆</button>}
    </>}
  </section>
}
