import { useState } from 'react'
import { runtimeBridge } from '../platform/native'
import type { CompanionRow } from '../platform/companion'

type Copy = { incidentId: string; usable: boolean; note?: string; preview?: Record<string, unknown>; localPreview?: Record<string, unknown> }
function copy(value: unknown): Copy | null {
  return value && typeof value === 'object' && 'incidentId' in value && typeof value.incidentId === 'string' ? value as Copy : null
}

export function WorkerRecovery({ row, onRefresh }: { row: CompanionRow; onRefresh: () => void }) {
  const task = row.recoveryRequired === true ? copy(row.recovery) : null
  const goal = copy(row.goalRecovery)
  const [acknowledged, setAcknowledged] = useState(false)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  if (!task && !goal) return null
  const usable = (!task || task.usable) && (!goal || goal.usable)
  return <details className="worker-recovery"><summary>恢复副本 · 待核对</summary>
    {[task, goal].map((item, index) => item && <div key={item.incidentId + index}>
      <strong>{index === 0 ? '工单副本' : '目标副本'}</strong><p>{item.note}</p>
      {Object.entries((item.usable ? item.preview : item.localPreview || item.preview) || {}).map(([key, value]) => {
        const label = ({ title: '标题', input: '原请求', summary: '先前结果', outcome: '预期结果', criteria: '完成条件', budgetUsage: '已记录用量', budget: '预算', writeSeq: '副本版本' } as Record<string, string>)[key]
        let display: string
        if (Array.isArray(value)) display = value.join('、')
        else if (value && typeof value === 'object') {
          const usage = value as Record<string, unknown>
          display = (typeof usage.tokens === 'number' ? usage.tokens : '未知') + ' tokens · ' +
            (typeof usage.calls === 'number' ? usage.calls : '未知') + ' 次调用 · ' +
            (typeof usage.milliseconds === 'number' ? Math.round(usage.milliseconds / 60000) : '未知') + ' 分钟'
        } else display = typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : ''
        return label && value !== null && value !== undefined && <p key={key}><strong>{label}</strong><br />{display}</p>
      })}
    </div>)}
    {usable ? <>
      <label><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />我已核对副本、已执行动作及剩余预算</label>
      <button type="button" disabled={!acknowledged || busy} onClick={() => {
        setBusy(true)
        void runtimeBridge.companionDashboard({ operation: 'workerControl', id: row.id, action: 'recover', recoveryAcknowledged: true,
          recoveryIncident: task?.incidentId, goalRecoveryIncident: goal?.incidentId })
          .then(() => { setMessage('已提交解锁，状态更新后可补充说明并继续原工单'); onRefresh() })
          .catch(() => setMessage('副本或任务状态已变化，请刷新后重新核对')).finally(() => setBusy(false))
      }}>核对后解锁</button>
    </> : <p>缺少可校验的完整副本。请查看会话历史或导入备份，再重新交办。</p>}
    {message && <p role="status">{message}</p>}
  </details>
}
