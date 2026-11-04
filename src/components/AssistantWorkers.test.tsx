import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn(), addWorkerDashboardListener: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantWorkers } from './AssistantWorkers'
it('resumes the saved permission request without resubmitting its task text', async () => {
  bridge.companionDashboard.mockImplementation((request: {operation:string}) => Promise.resolve(request.operation === 'workerPage'
    ? {items:[], total:0, submissions:[{commandId:'held-id',task:'交接报告',notice:'需要本地资料',held:true}]}
    : {message:'已提交原任务'}))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  expect(await screen.findByText(/待授权 · 交接报告/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button',{name:'继续原任务'}))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({operation:'workerPermission',id:'held-id',action:'resume'}))
  expect(await screen.findByText('已提交原任务')).toBeInTheDocument()
})
it('suspends visible settings reads without overwriting the draft when the task tab returns', async () => {
  vi.useFakeTimers()
  const interval = vi.spyOn(window, 'setInterval')
  let reads = 0
  bridge.addWorkerDashboardListener.mockResolvedValue({remove:vi.fn()})
  bridge.companionDashboard.mockImplementation(({operation}: {operation:string}) => {
    if (operation === 'superagentSettings') {
      reads++
      return Promise.resolve({config:{github:{repo:'team/project'}}})
    }
    return Promise.resolve(operation === 'workerPage' ? {items:[],total:0} : {})
  })
  const app = render(<AssistantWorkers onOpenSession={vi.fn()} />)
  try {
    const details = app.container.querySelector('details.worker-secondary-settings')!
    await act(async () => { details.setAttribute('open',''); details.dispatchEvent(new Event('toggle')); await Promise.resolve() })
    expect(reads).toBe(1)
    const input = screen.getByLabelText('仓库（owner/name）')
    fireEvent.change(input,{target:{value:'draft/project'}})
    app.rerender(<AssistantWorkers onOpenSession={vi.fn()} visible={false} />)
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
    expect(reads).toBe(1)
    app.rerender(<AssistantWorkers onOpenSession={vi.fn()} />)
    await act(async () => {})
    expect(reads).toBe(2)
    expect(input).toHaveValue('draft/project')
    expect(interval.mock.calls.some(([,delay]) => delay === 5000 || delay === 15000)).toBe(false)
  } finally { app.unmount(); interval.mockRestore(); bridge.addWorkerDashboardListener.mockReset(); vi.useRealTimers() }
})
it('opens the blocking predecessor from the dependent task card', async () => {
  bridge.companionDashboard.mockImplementation((request: {operation:string; id?:string}) => Promise.resolve(
    request.operation === 'workerPage' ? {items:[{id:'child',text:'汇总',state:'queued',waitReason:'等待前置任务「核对」· 失败，需重试',
      dependencyTarget:{id:'parent',title:'核对',state:'failed'}}],total:1} :
    {id:request.id,events:[],summary:'核对未完成',historyCount:0}))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  fireEvent.click(await screen.findByText('查看前置任务 · 核对'))
  expect(await screen.findByText('核对未完成')).toBeInTheDocument()
  expect(bridge.companionDashboard).toHaveBeenCalledWith({operation:'workerDetail',id:'parent',offset:0})
  fireEvent.change(screen.getByRole('textbox',{name:/确认现场后的继续说明/}),{target:{value:'材料已补齐'}})
  fireEvent.click(screen.getByRole('button',{name:'提交并继续'}))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({operation:'workerControl',id:'parent',action:'resume',task:'材料已补齐'}))
})
it('answers only the selected worker and leaves screen capture opt-in', async () => {
  bridge.companionDashboard.mockClear()
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerPage'
    ? { items: [{ id: 'task-one', text: '验收任务', state: 'waiting', question: '请选择颜色', sessionId: 'session-one', inputs: [{ id: 'i1', revision: 1, kind: 'request', text: '选择颜色', delivery: 'delivered', readAt: 1 }], reports: [{ revision: 1, summary: '请选择颜色' }] }] }
    : request.operation === 'workerDetail' ? { id: 'task-one', events: [{ type: 'input', id: 'i1', revision: 1, text: '选择颜色', delivery: 'delivered', readAt: 1 }], historyCount: 1 }
    : { enabled: false, active: false }))
  const open = vi.fn()
  render(<AssistantWorkers onOpenSession={open} />)
  expect(await screen.findByText('请选择颜色')).toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: /允许屏幕陪看/ })).not.toBeChecked()
  expect(bridge.companionDashboard).not.toHaveBeenCalledWith(expect.objectContaining({ operation: 'workerDetail' }))
  fireEvent.click(screen.getByText('任务详情'))
  expect(await screen.findByText(/已进入任务会话/)).toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox', { name: /你的回答/ }), { target: { value: '蓝色' } })
  fireEvent.click(screen.getByRole('button', { name: '提交并继续' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerControl', id: 'task-one', action: 'reply', task: '蓝色' }))
  fireEvent.click(screen.getByRole('button', { name: '查看任务会话' }))
  expect(open).toHaveBeenCalledWith('session-one')
})

it('queues an update on a completed task', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerPage'
    ? { items: [{ id: 'task-done', text: '已完成任务', state: 'completed', sessionId: 'session-done' }] }
    : { enabled: false, active: false }))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  const expand = await screen.findByRole('button', { name: '追加信息' })
  expect(screen.queryByRole('textbox', { name: /追加任务信息/ })).not.toBeInTheDocument()
  fireEvent.click(expand)
  const field = await screen.findByRole('textbox', { name: /追加任务信息/ })
  fireEvent.change(field, { target: { value: '补充范围' } })
  fireEvent.click(screen.getByRole('button', { name: '提交补充' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerControl', id: 'task-done', action: 'add', task: '补充范围' }))
})


it('keeps a historical task visible without offering actions when its ledger is missing', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerPage'
    ? { items: [{ id: 'lost-task', text: '旧任务', state: 'waiting', missingLedger: true, uiError: '找不到任务执行记录', sessionId: 'old-session' }] }
    : { enabled: false, active: false }))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  expect(await screen.findByText('找不到任务执行记录')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '提交并继续' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '取消任务' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '查看任务会话' })).toBeInTheDocument()
})

it('后台创作是显式任务类型，草稿预览绑定任务和修订', async () => {
  const revision = 'c'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerPage'
    ? { items: [{ id: 'worker-one', text: '生成工具', state: 'completed', mode: 'create', authoredDraft: { revision } }] } : { ok: true }))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  expect(screen.queryByRole('textbox', { name: '交办新任务' })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '手动交办' }))
  fireEvent.change(screen.getByLabelText('任务类型'), { target: { value: 'create' } })
  fireEvent.change(screen.getByRole('textbox', { name: '交办新任务' }), { target: { value: '制作工时换算器' } })
  fireEvent.click(screen.getByRole('button', { name: '交办' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerSubmit', task: '制作工时换算器', mode: 'create' }))
  fireEvent.click(await screen.findByRole('button', { name: '预览工具草稿' }))
  expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerPreviewDraft', id: 'worker-one', revision })
})

it('有原生订阅时，静置不扫描工单或屏幕状态，翻页只取该页', async () => {
  vi.useFakeTimers()
  const remove = vi.fn()
  let changed: (() => void) | undefined
  bridge.addWorkerDashboardListener.mockImplementation((fn: () => void) => { changed = fn; return Promise.resolve({ remove }) })
  let pageCalls = 0
  bridge.companionDashboard.mockImplementation((req: { operation: string; offset: number }) => {
    expect(req.operation).toBe('workerPage')
    pageCalls++
    return Promise.resolve({ total: 21, groups: ['目标甲'], items: [{ id: 'task-'+req.offset, text: '页首'+req.offset }] })
  })
  try {
    const app = render(<AssistantWorkers onOpenSession={vi.fn()} />)
    await act(async () => {})
    expect(screen.getByText('页首0')).toBeInTheDocument()
    expect(pageCalls).toBe(1)
    await act(async () => { vi.advanceTimersByTime(45000); await Promise.resolve() })
    expect(pageCalls).toBe(1)
    await act(async () => { changed?.(); await Promise.resolve() })
    expect(pageCalls).toBe(2)
    await act(async () => { fireEvent.click(screen.getByRole('button',{ name: '下一页' })); await Promise.resolve() })
    expect(screen.getByText('页首20')).toBeInTheDocument()
    expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerPage', group: '', filter: 'all', offset: 20, limit: 20 })
    app.unmount()
    await act(async () => {})
    expect(remove).toHaveBeenCalled()
  } finally { bridge.addWorkerDashboardListener.mockReset(); vi.useRealTimers() }
})

it('opens a completed source from the goal report while the filtered page is empty', async () => {
  bridge.companionDashboard.mockImplementation(({operation, group}: {operation:string; group?:string}) => {
    if (operation === 'workerGroups') return Promise.resolve({groups:['发布准备']})
    if (operation === 'workerDetail') return Promise.resolve({events:[], summary:'完整结论'})
    if (operation === 'workerPage' && group === '发布准备') return Promise.resolve({
      items:[], total:0, groupReport:{done:{count:1, items:[{id:'worker-A', title:'核对清单', state:'completed', note:'清单已核对'}]}},
    })
    return Promise.resolve({items:[], total:0})
  })
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  fireEvent.click(screen.getByRole('button',{name:'按目标筛选'}))
  await screen.findByRole('option',{name:'发布准备'})
  fireEvent.change(screen.getByLabelText('所属目标'),{target:{value:'发布准备'}})
  expect(await screen.findByText('清单已核对')).toBeInTheDocument()
  fireEvent.click(screen.getByText('核对清单', {selector:'summary'}))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({operation:'workerDetail', id:'worker-A', offset:0}))
})
