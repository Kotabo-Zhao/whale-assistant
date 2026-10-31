import { useEffect, useState } from 'react'
import { Bell, Brain, BookOpen, ListTodo, RefreshCw } from 'lucide-react'
import { runtimeBridge } from '../platform/native'
import { companionRows, type CompanionRow } from '../platform/companion'
import './AssistantOverview.css'

type Overview = { generatedAt: number; captureEnabled: boolean; notificationCount: number; memoryCount: number; knowledgeCount: number; categories: Record<string, number>; tasks: CompanionRow[]; reminders: CompanionRow[]; recurring: CompanionRow[] }
function parseOverview(raw: Record<string, unknown>): Overview {
  for (const key of ['generatedAt', 'notificationCount', 'memoryCount', 'knowledgeCount']) if (typeof raw[key] !== 'number' || !Number.isSafeInteger(raw[key]) || raw[key] < 0) throw new Error('invalid overview')
  if (typeof raw.captureEnabled !== 'boolean' || !raw.categories || typeof raw.categories !== 'object' || Array.isArray(raw.categories) || !Object.values(raw.categories).every(n => typeof n === 'number' && Number.isSafeInteger(n) && n >= 0)) throw new Error('invalid categories')
  return { ...raw, tasks: companionRows(raw.tasks), reminders: companionRows(raw.reminders), recurring: companionRows(raw.recurring) } as Overview
}
export function AssistantOverview({ onOpenTasks }: { onOpenTasks?: () => void }) {
  const [data, setData] = useState<Overview | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let live = true
    setLoading(true); setFailed(false)
    const readDashboard = (runtimeBridge as { companionDashboard?: (request: { operation: 'overview' }) => Promise<Record<string, unknown>> }).companionDashboard
    if (!readDashboard) { setFailed(true); setLoading(false); return () => { live = false } }
    void Promise.resolve(readDashboard({ operation: 'overview' })).then(parseOverview).then(value => { if (live) setData(value) }).catch(() => { if (live) setFailed(true) }).finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [revision])
  const upcoming = data ? [...data.reminders, ...data.recurring].filter(row => row.state === 'scheduled' && Number(row.nextAt ?? row.at) > Date.now()).sort((a,b) => Number(a.nextAt ?? a.at) - Number(b.nextAt ?? b.at)).slice(0, 3) : []
  return <section className="assistant-overview" aria-label="本地助手概览">
    <header><div><span className="eyebrow">PERSONAL ASSISTANT</span><h2>我的助手</h2><p>近期事项、提醒与本地信息摘要</p></div><button className="icon-button" aria-label="刷新助手概览" disabled={loading} onClick={() => setRevision(v => v + 1)}><RefreshCw size={18} /></button></header>
    {loading && <p>正在读取手机上的助手数据…</p>}
    {failed && <p role="status">暂时无法读取概览。{data ? '下方保留上次结果，请刷新后核对。' : '不会把读取失败当作没有事项。'}</p>}
    {data && <><div className="assistant-metrics"><div><Bell size={19} /><strong>{data.notificationCount}</strong><span>已保存通知</span></div><div><Brain size={19} /><strong>{data.memoryCount}</strong><span>长期记忆</span></div><div><BookOpen size={19} /><strong>{data.knowledgeCount}</strong><span>本地资料</span></div><div><ListTodo size={19} /><strong>{data.tasks.length}</strong><span>近期待办</span></div></div>
      <p className="assistant-overview-note">{data.captureEnabled ? '基于本地保留的通知按关键词分类，不代表重要性判断。' : '通知采集未开启；已有数据不会因此删除。'}</p>
      <div className="assistant-category-chips">{Object.entries(data.categories).map(([name,count]) => <span key={name}>{name} · {count}</span>)}</div>
      <h3>待核对与待处理</h3>{data.tasks.length ? <ul>{data.tasks.map(row => <li key={row.id}><strong>{row.text}</strong><time>{row.state === 'candidate' ? '待核实' : '待处理'}{typeof row.reminderAtMs === 'number' ? ` · 提醒 ${new Date(row.reminderAtMs).toLocaleString()}` : ''}</time></li>)}</ul> : <p className="assistant-overview-note">当前没有待核对或待处理的事项。</p>}
      {onOpenTasks && <button className="assistant-overview-link" type="button" onClick={onOpenTasks}>查看全部事项</button>}
      <h3>接下来</h3>{upcoming.length ? <ul>{upcoming.map(row => <li key={row.id}><strong>{row.text}</strong><time>{new Date(Number(row.nextAt ?? row.at)).toLocaleString()}</time></li>)}</ul> : <p className="assistant-overview-note">当前没有已安排的未来提醒。</p>}
      <footer>更新于 {new Date(data.generatedAt).toLocaleString()} · 在“事项”管理待办，在“记忆”管理资料和长期记忆</footer>
    </>}
  </section>
}
