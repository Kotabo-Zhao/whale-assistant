import { WorkerTaskDetail } from './WorkerTaskDetail'

const sections = { done: '已完成', attention: '需处理', working: '执行与排队', cancelled: '已取消' } as const
type Sample = { id: string; title: string; state: string; note: string; revision?: number }
type Bucket = { count: number; items: Sample[] }

function bucket(value: unknown): Bucket | undefined {
  if (!value || typeof value !== 'object' || !('count' in value) || typeof value.count !== 'number' ||
      !('items' in value) || !Array.isArray(value.items)) return undefined
  const items = (value.items as unknown[]).filter((item): item is Sample => {
    const row = item as Partial<Sample> | null
    return Boolean(row && typeof row.id === 'string' && typeof row.title === 'string' &&
      typeof row.state === 'string' && typeof row.note === 'string')
  })
  return { count: value.count, items }
}

export function WorkerGroupReport({ group, report }: { group: string; report: Record<string, unknown> }) {
  return <section className="assistant-task-card" aria-label="目标汇总">
    <h3>{group} · 目标汇总</h3>
    {Object.entries(sections).map(([key, name]) => {
      const data = bucket(report[key])
      if (!data?.count) return null
      return <section key={key}><h4>{name} · {data.count}</h4>
        {data.items.map(item => <div key={item.id}>
          <WorkerTaskDetail row={{id:item.id,text:item.title,state:item.state,writeSeq:String(item.revision ?? item.note)}} label={item.title} />
          <p className="worker-text">{item.note || (key === 'done' ? '工单已完成，暂无结果摘要' : '暂无进展摘要')}</p>
        </div>)}
        {data.count > data.items.length && <small>显示最近 {data.items.length} 项，共 {data.count} 项</small>}
      </section>
    })}
  </section>
}
