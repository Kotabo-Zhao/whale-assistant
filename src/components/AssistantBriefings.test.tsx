import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantTasks } from './AssistantTasks'

beforeEach(() => vi.clearAllMocks())
it('只展示桌宠的总结，继续对话回到前台会话，不露出 Worker 卡片', async () => {
  bridge.companionDashboard.mockResolvedValue({ items: [
    { id: 'receipt', text: 'RAW_WORKER_RECEIPT', owner: 'session-child' },
    { id: 'briefing', text: '两份安排我已经核对过，还需要你确认出发日期。', kind: 'coordination', owner: 'session-owner' },
  ] })
  const open = vi.fn()
  render(<AssistantTasks onOpenSession={open} />)
  expect(await screen.findByText(/两份安排我已经核对过/)).toBeInTheDocument()
  expect(screen.queryByText('RAW_WORKER_RECEIPT')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '后台任务' })).not.toBeInTheDocument()
  expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'assistantBriefings' })
  fireEvent.click(screen.getByRole('button', { name: '继续聊这件事' }))
  expect(open).toHaveBeenCalledWith('session-owner')
})
