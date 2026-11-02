import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { companionRows, type CompanionRow } from '../platform/companion'
import { SuperagentSettings } from './SuperagentSettings'

const states: Record<string, string> = { queued: '排队中', starting: '启动中', running: '执行中', waiting: '等你回答', blocked: '遇到阻碍', interrupted: '中断待确认', failed: '失败', cancelling: '正在取消', cancelled: '已取消', completed: '已完成' }
export function AssistantWorkers({ onOpenSession }: { onOpenSession: (id: string) => void }) {
  const [rows, setRows] = useState<CompanionRow[]>([])
  const [watch, setWatch] = useState({ enabled: false, active: false })
  const [message, setMessage] = useState('')
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    let inFlight = false
    const load = async (): Promise<void> => {
      if (inFlight) return
      inFlight = true
      try {
        const [tasks, screen] = await Promise.all([
          runtimeBridge.companionDashboard({ operation: 'workerList' }),
          runtimeBridge.companionDashboard({ operation: 'screenWatchStatus' }),
        ])
        if (active) { setRows(companionRows(tasks.items)); setWatch({ enabled: screen.enabled === true, active: screen.active === true }) }
      } catch { if (active) setMessage('后台任务状态暂时无法读取。') }
      finally { inFlight = false }
    }
    void load()
    const timer = window.setInterval(() => { if (document.visibilityState !== 'hidden') void load() }, 3000)
    return () => { active = false; window.clearInterval(timer) }
  }, [revision])
  const control = async (row: CompanionRow, action: 'cancel' | 'resume' | 'reply' | 'add'): Promise<void> => {
    setBusy(row.id)
    try {
      await runtimeBridge.companionDashboard({ operation: 'workerControl', id: row.id, action, task: answers[row.id] || '' })
      setAnswers(old => ({ ...old, [row.id]: '' }))
      setMessage('操作已排队，运行中的 Harness 会处理；状态以任务卡片为准。'); setRevision(value => value + 1)
    } catch { setMessage('未能提交，请刷新后重试。') }
    finally { setBusy(null) }
  }
  const screenControl = async (enabled?: boolean): Promise<void> => {
    try {
      await runtimeBridge.companionDashboard(enabled === undefined ? { operation: 'screenWatchStop' } : { operation: 'screenWatchSet', enabled })
      setRevision(value => value + 1)
    } catch { setMessage('屏幕陪看设置未能保存。') }
  }
  return <section aria-label="后台协作与屏幕陪看">
    <h3>后台协作</h3><SuperagentSettings /><p>多个只读任务可并行；操作手机屏幕的任务逐个执行。聊天不会因后台任务而锁住。</p>
    <label><input type="checkbox" checked={watch.enabled} onChange={event => { void screenControl(event.target.checked) }} />允许屏幕陪看</label>
    <p>开启后，只有你要求陪看时才采集；关键画面会发给当前视觉模型，每次最多两分钟。{watch.active ? '正在采集。' : '当前未采集。'}</p>
    {watch.active && <button type="button" onClick={() => { void screenControl() }}>立即停止看屏幕</button>}
    {message && <p role="status">{message}</p>}
    <div className="assistant-task-list">{rows.map(row => <article key={row.id} className="assistant-task-card">
      <div><strong>{row.text}</strong><small>{states[String(row.state)] || '状态待确认'} · {row.mode === 'device' ? '手机操作' : '读取与整理'}</small>
        {typeof row.progress === 'string' && row.progress && <p>进展：{row.progress}</p>}
        {typeof row.question === 'string' && row.question && <p>{row.question}</p>}
        {typeof row.summary === 'string' && row.summary && <details><summary>查看结果</summary><p style={{ whiteSpace: 'pre-wrap' }}>{row.summary}</p></details>}
        {Array.isArray(row.inputs) && row.inputs.length > 0 && <details><summary>查看任务记录</summary>
          <ol>{(row.inputs as Array<Record<string, unknown>>).map((input, index) => {
            const status = input.readAt ? '已进入任务会话' : input.delivery === 'delivered' ? '已投递' : input.delivery === 'pending' ? '待投递' : input.delivery === 'sending' ? '正在投递' : input.delivery === 'cancelled' ? '已取消' : '投递状态待确认'
            return <li key={String(input.id || index)}>{input.kind === 'reply' ? '补充回答' : input.kind === 'resume' ? '继续说明' : input.kind === 'update' ? '追加信息' : input.kind === 'recovery' ? '自动恢复只读任务' : '任务输入'} · {status}
              {typeof input.text === 'string' && <p style={{ whiteSpace: 'pre-wrap' }}>{input.text}</p>}
              {Array.isArray(row.progressHistory) && (row.progressHistory as Array<Record<string, unknown>>).filter(progress => progress.revision === input.revision).map((progress, i) =>
                <p key={i} style={{ whiteSpace: 'pre-wrap' }}>进展：{typeof progress.summary === 'string' ? progress.summary : ''}</p>)}
              {Array.isArray(row.reports) && (row.reports as Array<Record<string, unknown>>).filter(report => report.revision === input.revision).map((report, i) =>
                <p key={i} style={{ whiteSpace: 'pre-wrap' }}>回执：{typeof report.summary === 'string' ? report.summary : ''}</p>)}
            </li>
          })}</ol></details>}
        {typeof row.uiError === 'string' && row.uiError && <p>{row.uiError}</p>}
        {row.missingLedger !== true && ['queued', 'starting', 'running', 'completed'].includes(String(row.state)) && <label>追加任务信息（当前轮结束后处理）
          <textarea value={answers[row.id] || ''} onChange={event => setAnswers(old => ({ ...old, [row.id]: event.target.value }))} />
          <button type="button" disabled={busy !== null || !answers[row.id]?.trim()} onClick={() => { void control(row, 'add') }}>追加信息</button></label>}
        {row.missingLedger !== true && ['waiting', 'blocked', 'interrupted', 'failed'].includes(String(row.state)) && <label>{row.state === 'waiting' ? '你的回答' : '确认现场后的继续说明'}
          <textarea value={answers[row.id] || ''} onChange={event => setAnswers(old => ({ ...old, [row.id]: event.target.value }))} />
          <button type="button" disabled={busy !== null || !answers[row.id]?.trim()} onClick={() => { void control(row, row.state === 'waiting' ? 'reply' : 'resume') }}>提交并继续</button></label>}
      </div>
      <div className="assistant-task-actions"><button type="button" onClick={() => { if (typeof row.sessionId === 'string') onOpenSession(row.sessionId) }}>查看任务会话</button>
        {row.missingLedger !== true && !['completed', 'failed', 'cancelled', 'cancelling'].includes(String(row.state)) && <button type="button" disabled={busy !== null} onClick={() => { void control(row, 'cancel') }}>取消任务</button>}
      </div>
    </article>)}</div>
  </section>
}
