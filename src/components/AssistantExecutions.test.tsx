import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it, vi } from 'vitest'

const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { AssistantExecutions } from './AssistantExecutions'

it('shows a waiting request and opens its original session without resubmitting', async () => {
  bridge.companionDashboard.mockResolvedValue({ items: [{ id: 'request-1', text: '整理资料', state: 'waiting', sessionId: 'session-existing' }] })
  const open = vi.fn()
  render(<AssistantExecutions onOpenSession={open} />)
  expect(await screen.findByText('等你回答')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '进入原会话' }))
  expect(open).toHaveBeenCalledWith('session-existing')
  expect(bridge.companionDashboard).toHaveBeenCalledTimes(1)
})
