import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'

const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantTasks } from './AssistantTasks'

beforeEach(() => { vi.clearAllMocks(); bridge.companionDashboard.mockResolvedValue({ items: [] }) })

it('事项入口读取统一台账，并用读取时的状态与版本更新单项', async () => {
  const revision = 'a'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'taskList'
    ? { items: [{ id: 'task-1', text: '核对合同', state: 'candidate', sourceKind: 'shared_material', sourceRecordId: '123e4567-e89b-12d3-a456-426614174000', revision }] }
    : { ok: true }))
  render(<AssistantTasks />)
  expect(await screen.findByText('核对合同')).toBeInTheDocument()
  expect(screen.getByText(/资料整理 · 已关联资料/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '标记待处理' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'taskUpdate', id: 'task-1', expectedState: 'candidate', expectedRevision: revision, state: 'open' }))
  expect(screen.getByRole('status')).toHaveTextContent('事项已标记为待处理')
})

it('事项读取失败不伪装成空清单', async () => {
  bridge.companionDashboard.mockRejectedValue(new Error('unavailable'))
  render(<AssistantTasks />)
  expect(await screen.findByRole('status')).toHaveTextContent('不会把读取失败显示成没有事项')
  expect(screen.queryByText('当前筛选下没有事项。')).not.toBeInTheDocument()
})

it('安排提醒要求用户提交未来时间并沿用读取时的版本', async () => {
  const revision = 'b'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'taskList'
    ? { items: [{ id: 'task-2', text: '预约复诊', state: 'candidate', sourceKind: 'notification', revision }] }
    : { ok: true }))
  render(<AssistantTasks />)
  await screen.findByText('预约复诊')
  fireEvent.click(screen.getByRole('button', { name: '安排提醒' }))
  const input = screen.getByLabelText('提醒时间')
  fireEvent.change(input, { target: { value: '2030-01-02T03:04' } })
  fireEvent.click(screen.getByRole('button', { name: '确认安排' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({ operation: 'taskUpdate', id: 'task-2', expectedState: 'candidate', expectedRevision: revision, reminderAtMs: new Date('2030-01-02T03:04').getTime() })))
})
