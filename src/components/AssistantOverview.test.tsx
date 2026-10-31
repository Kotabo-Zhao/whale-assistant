import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantOverview } from './AssistantOverview'
it('概览显示原生统计、近期事项和未来提醒，不启动模型', async () => {
  bridge.companionDashboard.mockResolvedValue({ generatedAt: Date.now(), captureEnabled: false, notificationCount: 4, memoryCount: 2, knowledgeCount: 1, categories: { 消息: 4 }, tasks: [{ id: 't', text: '确认行程', state: 'candidate' }], reminders: [{ id: 'r', text: '取快递', at: Date.now() + 600000, state: 'scheduled' }], recurring: [] })
  render(<AssistantOverview />)
  expect(await screen.findByText('取快递')).toBeInTheDocument()
  expect(screen.getByText('确认行程')).toBeInTheDocument()
  expect(screen.getByText('消息 · 4')).toBeInTheDocument()
  expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'overview' })
})
it('首页事项摘要只跳转到统一事项入口', async () => {
  const onOpenTasks = vi.fn()
  bridge.companionDashboard.mockResolvedValue({ generatedAt: Date.now(), captureEnabled: false, notificationCount: 0, memoryCount: 0, knowledgeCount: 0, categories: {}, tasks: [], reminders: [], recurring: [] })
  render(<AssistantOverview onOpenTasks={onOpenTasks} />)
  fireEvent.click(await screen.findByRole('button', { name: '查看全部事项' }))
  expect(onOpenTasks).toHaveBeenCalledTimes(1)
})
it('缺失的原生数据不能显示为零条事项', async () => {
  bridge.companionDashboard.mockResolvedValue({})
  render(<AssistantOverview />)
  expect(await screen.findByRole('status')).toHaveTextContent('不会把读取失败当作没有事项')
  expect(screen.queryByText('当前没有已安排的未来提醒。')).not.toBeInTheDocument()
})
