import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { WhaleChatScreen } from './WhaleChatScreen'

const mockChat = vi.fn<RuntimeBridge['whaleNativeChat']>()

const mockBridge = {
  whaleNativeChat: (req: Parameters<RuntimeBridge['whaleNativeChat']>[0]) => mockChat(req),
  companionDashboard: undefined,
  addWhaleChatListener: undefined,
  addKnowledgeCapturesListener: undefined,
  addAssistantBriefingsListener: undefined,
} as unknown as RuntimeBridge

describe('WhaleChatScreen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    mockChat.mockResolvedValue({
      reply: '已收到，正在为你处理。',
      steps: [{ tool: 'whale_device_status', label: '读取手机综合状态', ok: true, durationMs: 42 }],
      model: 'deepseek-chat',
      elapsedMs: 320,
      mode: 'native_direct_sse',
    })
  })

  it('renders welcome state and sends a native chat turn with tool receipt', async () => {
    render(
      <WhaleChatScreen
        bridge={mockBridge}
        onOpenMemory={vi.fn()}
        onOpenTasks={vi.fn()}
        onOpenWorkshop={vi.fn()}
        onOpenSettings={vi.fn()}
      />,
    )

    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: '帮我查一下手机状态' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))

    await waitFor(() => {
      expect(mockChat).toHaveBeenCalledTimes(1)
    })
    expect(await screen.findByText('已收到，正在为你处理。')).toBeTruthy()
  })

  it('opens the session drawer from the header button', () => {
    render(
      <WhaleChatScreen
        bridge={mockBridge}
        onOpenMemory={vi.fn()}
        onOpenTasks={vi.fn()}
        onOpenWorkshop={vi.fn()}
        onOpenSettings={vi.fn()}
      />,
    )

    const listBtn = screen.getByRole('button', { name: /会话列表/ })
    fireEvent.click(listBtn)
    expect(screen.getByRole('complementary', { name: '本地会话管理' })).toBeTruthy()
  })
})
