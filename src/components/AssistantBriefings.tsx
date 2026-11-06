import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { companionRows, type CompanionRow } from '../platform/companion'

/** Front-agent prose only. Worker receipts and execution metadata never enter this view. */
export function AssistantBriefings({ onOpenSession }: { onOpenSession: (id: string) => void }) {
  const [items, setItems] = useState<CompanionRow[]>([])
  const [message, setMessage] = useState('正在听小鲸整理近况…')
  useEffect(() => {
    let active = true, loading = false
    const refresh = async () => {
      if (loading || document.hidden) return
      loading = true
      try {
        const result = await runtimeBridge.companionDashboard({ operation: 'assistantBriefings' })
        const next = companionRows(result.items).filter(row => row.kind === 'coordination')
        if (active) { setItems(next); setMessage(next.length ? '' : '有值得告诉你的进展，小鲸会主动来聊。') }
      } catch { if (active) setMessage('暂时没能读取近况，稍后会再试。') }
      finally { loading = false }
    }
    void refresh()
    const timer = window.setInterval(() => { void refresh() }, 8000)
    const onVisible = () => { void refresh() }
    document.addEventListener('visibilitychange', onVisible)
    return () => { active = false; window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [])
  return <div className="assistant-task-list" aria-label="小鲸近况">
    {message && <p role="status">{message}</p>}
    {items.map(row => <article key={row.id} className="assistant-task-card assistant-briefing">
      <div><strong>小鲸</strong><p style={{ whiteSpace: 'pre-wrap' }}>{row.text}</p></div>
      {typeof row.owner === 'string' && /^session-[A-Za-z0-9._-]{1,190}$/.test(row.owner) &&
        <button type="button" onClick={() => onOpenSession(row.owner as string)}>继续聊这件事</button>}
    </article>)}
  </div>
}
