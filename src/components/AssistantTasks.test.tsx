import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'

const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantTasks } from './AssistantTasks'

beforeEach(() => { vi.clearAllMocks(); bridge.companionDashboard.mockResolvedValue({ items: [] }) })

it('事项入口读取统一台账，并用读取时的状态与版本更新单项', async () => {
  const revision = 'a'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'taskList'
    ? { items: [{ id: 'task-1', text: '核对合同', state: 'candidate', sourceKind: 'shared_material', revision }] }
    : { ok: true }))
  render(<AssistantTasks />)
  expect(await screen.findByText('核对合同')).toBeInTheDocument()
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
