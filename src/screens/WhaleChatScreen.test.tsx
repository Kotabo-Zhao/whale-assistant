import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge, WhaleNativeChatStreamEvent } from '../platform/types'
import { WhaleChatScreen } from './WhaleChatScreen'

describe('WhaleChatScreen', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('sends a message via whaleNativeChat, renders inline action receipts, and manages multiple sessions', async () => {
    let emit: ((event: WhaleNativeChatStreamEvent) => void) | undefined
    const whaleNativeChat = vi.fn().mockImplementation((req: { requestId?: string; sessionId?: string }) => {
      emit?.({
        requestId: req.requestId ?? '',
        sessionId: req.sessionId ?? 'whale-main',
        type: 'step',
        step: { tool: 'whale_secretary_add', label: '记录秘书事项', ok: true, durationMs: 18 },
      })
      emit?.({
        requestId: req.requestId ?? '',
        sessionId: req.sessionId ?? 'whale-main',
        type: 'delta',
        text: '好的，已记下提醒',
      })
      return Promise.resolve({
        reply: '好的，已为你记下明早九点的提醒。',
        steps: [{ tool: 'whale_secretary_add', label: '记录秘书事项', ok: true, durationMs: 18 }],
        model: 'deepseek-chat',
        elapsedMs: 420,
        mode: 'native_direct_sse',
      })
    })
    const addWhaleChatListener = vi.fn().mockImplementation((listener: (event: WhaleNativeChatStreamEvent) => void) => {
      emit = listener
      return Promise.resolve({ remove: vi.fn().mockResolvedValue(undefined) })
    })
    const bridge = { whaleNativeChat, addWhaleChatListener } as unknown as RuntimeBridge
    const onNavigate = vi.fn()

    render(<WhaleChatScreen bridge={bridge} onNavigate={onNavigate} />)
    fireEvent.change(screen.getByLabelText('输入消息'), { target: { value: '明早九点提醒我开会' } })
    fireEvent.click(screen.getByLabelText('发送'))

    await waitFor(() => expect(screen.getByText('好的，已为你记下明早九点的提醒。')).toBeInTheDocument())
    expect(screen.getByText('已调用 1 项原生能力')).toBeInTheDocument()
    // Inline action receipt card appears and navigates directly to assistant-tasks
    fireEvent.click(screen.getByRole('button', { name: '查看事项' }))
    expect(onNavigate).toHaveBeenCalledWith('assistant-tasks')

    // Paste a URL -> auto archive suggestion pill appears
    fireEvent.change(screen.getByLabelText('输入消息'), { target: { value: 'https://example.com/article' } })
    expect(screen.getByText('顺手存入资料库并总结')).toBeInTheDocument()

    // Open sessions drawer and create a new session
    fireEvent.click(screen.getByLabelText('会话列表'))
    expect(screen.getByText(/对话会话/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('新建会话'))
    expect(screen.getByText('今天想一起做点什么？')).toBeInTheDocument()

    // Switch back to the first session and verify history persisted
    fireEvent.click(screen.getByLabelText('会话列表'))
    fireEvent.click(screen.getByText('明早九点提醒我开会'))
    expect(screen.getByText('好的，已为你记下明早九点的提醒。')).toBeInTheDocument()
  })
})
