import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantWorkers } from './AssistantWorkers'
it('answers only the selected worker and leaves screen capture opt-in', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerList'
    ? { items: [{ id: 'task-one', text: '验收任务', state: 'waiting', question: '请选择颜色', sessionId: 'session-one', inputs: [{ id: 'i1', revision: 1, kind: 'request', text: '选择颜色', delivery: 'delivered', readAt: 1 }], reports: [{ revision: 1, summary: '请选择颜色' }] }] }
    : { enabled: false, active: false }))
  const open = vi.fn()
  render(<AssistantWorkers onOpenSession={open} />)
  expect(await screen.findByText('请选择颜色')).toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: /允许屏幕陪看/ })).not.toBeChecked()
  expect(screen.getByText('查看任务记录')).toBeInTheDocument()
  expect(screen.getByText(/已进入任务会话/)).toBeInTheDocument()
  fireEvent.change(screen.getByRole('textbox', { name: /你的回答/ }), { target: { value: '蓝色' } })
  fireEvent.click(screen.getByRole('button', { name: '提交并继续' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerControl', id: 'task-one', action: 'reply', task: '蓝色' }))
  fireEvent.click(screen.getByRole('button', { name: '查看任务会话' }))
  expect(open).toHaveBeenCalledWith('session-one')
})

it('queues an update on a completed task', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerList'
    ? { items: [{ id: 'task-done', text: '已完成任务', state: 'completed', sessionId: 'session-done' }] }
    : { enabled: false, active: false }))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  const field = await screen.findByRole('textbox', { name: /追加任务信息/ })
  fireEvent.change(field, { target: { value: '补充范围' } })
  fireEvent.click(screen.getByRole('button', { name: '追加信息' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerControl', id: 'task-done', action: 'add', task: '补充范围' }))
})


it('keeps a historical task visible without offering actions when its ledger is missing', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'workerList'
    ? { items: [{ id: 'lost-task', text: '旧任务', state: 'waiting', missingLedger: true, uiError: '找不到任务执行记录', sessionId: 'old-session' }] }
    : { enabled: false, active: false }))
  render(<AssistantWorkers onOpenSession={vi.fn()} />)
  expect(await screen.findByText('找不到任务执行记录')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '提交并继续' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '取消任务' })).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '查看任务会话' })).toBeInTheDocument()
})
