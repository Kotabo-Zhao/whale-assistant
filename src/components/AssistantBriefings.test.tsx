import { act, fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn(), addAssistantBriefingsListener: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantTasks } from './AssistantTasks'

beforeEach(() => {
  vi.clearAllMocks()
  bridge.addAssistantBriefingsListener.mockResolvedValue({ remove: vi.fn() })
})
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

it('读取失败会重试，成功后只在有新转述时刷新', async () => {
  vi.useFakeTimers()
  let notify!: () => void
  const remove = vi.fn()
  bridge.addAssistantBriefingsListener.mockImplementation((listener: () => void) => {
    notify = listener
    return Promise.resolve({ remove })
  })
  bridge.companionDashboard.mockRejectedValueOnce(new Error('暂时不可用')).mockResolvedValue({ items: [] })
  const page = render(<AssistantTasks onOpenSession={vi.fn()} />)
  try {
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(screen.getByRole('status')).toHaveTextContent('稍后会再试')
    await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
    expect(bridge.companionDashboard).toHaveBeenCalledTimes(2)
    bridge.companionDashboard.mockResolvedValue({ items: [{ id: 'new', kind: 'coordination', text: '新的安排已确定' }] })
    await act(async () => { notify(); await Promise.resolve() })
    expect(screen.getByText('新的安排已确定')).toBeVisible()
  } finally {
    page.unmount()
    await Promise.resolve()
    vi.useRealTimers()
  }
  expect(remove).toHaveBeenCalledOnce()
})
