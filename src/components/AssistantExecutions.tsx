import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { companionRows, type CompanionRow } from '../platform/companion'

const stateLabel = (state: unknown): string => state === 'running' ? '进行中' : state === 'waiting' ? '等你回答' : state === 'permission' ? '等待权限确认' : state === 'failed' ? '未完成 · 查看原因' : state === 'completed' ? '已完成' : '连接已中断 · 请回会话确认'

/** Status cards point to the original session; opening a card never submits another prompt. */
export function AssistantExecutions({ onOpenSession }: { onOpenSession: (id: string) => void }) {
  const [rows, setRows] = useState<CompanionRow[]>([])
  const [error, setError] = useState('')
  const [offset, setOffset] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [pageCount, setPageCount] = useState(1)
  useEffect(() => {
    let active = true
    const load = async (): Promise<void> => {
      setLoading(true)
      try {
        const all: CompanionRow[] = []
        let next: number | null = 0
        for (let page = 0; page < pageCount && next !== null; page++) {
          const result = await runtimeBridge.companionDashboard({ operation: 'executionList', offset: next })
          all.push(...companionRows(result.items))
          next = typeof result.nextOffset === 'number' ? result.nextOffset : null
        }
        if (active) { setRows(all); setOffset(next); setError('') }
      } catch { if (active) setError('执行记录暂时无法读取，请稍后刷新。') }
      finally { if (active) setLoading(false) }
    }
    void load()
    const timer = window.setInterval(() => { if (document.visibilityState !== 'hidden') void load() }, 5000)
    return () => { active = false; window.clearInterval(timer) }
  }, [pageCount])
  return <section aria-label="Agent 执行任务">
    <h3>Agent 执行任务</h3><p>桌宠委托给 Agent 的任务。进入原会话查看完整过程、回答追问或处理权限。</p>
    {error && <p role="status">{error}</p>}
    {!error && !loading && rows.length === 0 && <p>还没有执行任务。</p>}
    <div className="assistant-task-list">{rows.map(row => <article className="assistant-task-card" key={row.id}>
      <div><strong>{row.text}</strong><small>{stateLabel(row.state)}{typeof row.updatedAt === 'number' ? ` · ${new Date(row.updatedAt).toLocaleString()}` : ''}</small></div>
      <div className="assistant-task-actions"><button type="button" onClick={() => { if (typeof row.sessionId === 'string') onOpenSession(row.sessionId) }}>进入原会话</button></div>
    </article>)}</div>
    {offset !== null && <button type="button" disabled={loading} onClick={() => setPageCount(count => count + 1)}>加载更多执行记录</button>}
  </section>
}
