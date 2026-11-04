import { useEffect, useState, type ReactNode } from 'react'
import { runtimeBridge } from '../platform/native'
import type { CompanionRow } from '../platform/companion'

type Part = Record<string, unknown>
const parts = (value: unknown): Part[] => Array.isArray(value) ? value.filter((item): item is Part => item != null && typeof item === 'object') : []
const deliveryLabel = (input: Part) => input.readAt ? '已进入任务会话' : input.delivery === 'delivered' ? '已投递' : input.delivery === 'pending' ? '待投递' :
  input.delivery === 'sending' ? '正在投递' : input.delivery === 'cancelled' ? '已取消' : '投递状态待确认'

export function WorkerDependencyDetail({ target, action }: { target: unknown; action: (row: CompanionRow) => ReactNode }) {
  if (!target || typeof target !== 'object' || !('id' in target) || typeof target.id !== 'string' ||
    !('title' in target) || typeof target.title !== 'string') return null
  const row = { id: target.id, text: target.title, state: 'state' in target ? String(target.state) : '',
    writeSeq: `${'state' in target ? String(target.state) : ''}/${'updatedAt' in target ? String(target.updatedAt) : ''}` }
  return <WorkerTaskDetail key={row.id} row={row} label={`查看前置任务 · ${target.title}`}>{action(row)}</WorkerTaskDetail>
}

export function WorkerTaskDetail({ row, label = '任务详情', children }: { row: CompanionRow; label?: string; children?: ReactNode }) {
  const [open, setOpen] = useState(false)
  const [offset, setOffset] = useState(0)
  const [detail, setDetail] = useState<Part | null>(null)
  const [failed, setFailed] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!open) return
    let current = true
    setFailed(false); setDetail(null)
    void runtimeBridge.companionDashboard({ operation: 'workerDetail', id: row.id, offset }).then(data => {
      if (data.id !== row.id || !Array.isArray(data.events)) throw new Error('invalid detail')
      if (current) setDetail(data)
    }).catch(() => { if (current) setFailed(true) })
    return () => { current = false }
  }, [open, row.id, row.writeSeq, offset, retry])
  const events = parts(detail?.events)
  return <details onToggle={event => {
    const expanded = event.currentTarget.open
    setOpen(expanded)
    if (!expanded) { setOffset(0); setDetail(null) }
  }}><summary>{label}</summary>
    {open && failed && <p>无法读取此工单。 <button type="button" onClick={() => setRetry(n => n + 1)}>重试</button></p>}
    {open && !failed && !detail && <p>正在读取任务记录…</p>}
    {open && detail && <>
      {([['summary','完整结果'], ['scope','任务范围'], ['decisionReason','接取依据'], ['decisionEvidence','判断依据'], ['question','待回答'], ['progress','当前进展']] as const)
        .map(([key,label]) => typeof detail[key] === 'string' && detail[key] !== '' && <div key={key}><strong>{label}</strong><p className="worker-text">{detail[key]}</p></div>)}
      {events.length > 0 && <><strong>任务记录</strong><ol>{events.map((event,index) => <li key={typeof event.id === 'string' ? event.id : index}>
        {typeof event.revision === 'number' && <>第 {event.revision} 轮 · </>}
        {event.type === 'progress' ? '进展' : event.type === 'report' ? '回执' : event.kind === 'reply' ? '补充回答' :
          event.kind === 'resume' ? '继续说明' : event.kind === 'update' ? '追加信息' : event.kind === 'recovery' ? '自动恢复只读任务' : '任务输入'}
        {event.type === 'input' && <> · {deliveryLabel(event)}</>}
        <p className="worker-text">{typeof event.text === 'string' ? event.text : typeof event.summary === 'string' ? event.summary : ''}</p>
      </li>)}</ol></>}
      {detail.missingLedger !== true && detail.authorizationRevoked !== true && children}
      {offset > 0 && <button type="button" onClick={() => setOffset(Math.max(0,offset-12))}>较新记录</button>}
      {typeof detail.historyCount === 'number' && offset + events.length < detail.historyCount && <button type="button" onClick={() => setOffset(offset+12)}>更早记录</button>}
    </>}
  </details>
}
