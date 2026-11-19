import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RuntimeBridge, WhaleNativeChatStreamEvent } from '../platform/types'
import { WhaleChatScreen } from './WhaleChatScreen'

describe('WhaleChatScreen', () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it('sends a message via whaleNativeChat, renders streamed deltas, and manages multiple sessions', async () => {
    let emit: ((event: WhaleNativeChatStreamEvent) => void) | undefined
    const whaleNativeChat = vi.fn().mockImplementation((req: { requestId?: string; sessionId?: string }) => {
      emit?.({
        requestId: req.requestId ?? '',
        sessionId: req.sessionId ?? 'whale-main',
        type: 'step',
        step: { tool: 'whale_memory_search', label: '已检索记忆（2 条）', ok: true, durationMs: 18 },
      })
      emit?.({
        requestId: req.requestId ?? '',
        sessionId: req.sessionId ?? 'whale-main',
        type: 'delta',
        text: '你好，流式',
      })
      return Promise.resolve({
        reply: '你好，流式回复已完成。',
        steps: [{ tool: 'whale_memory_search', label: '已检索记忆（2 条）', ok: true, durationMs: 18 }],
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
    fireEvent.change(screen.getByLabelText('输入消息'), { target: { value: '帮我查一下记忆' } })
    fireEvent.click(screen.getByLabelText('发送'))

    await waitFor(() => expect(screen.getByText('你好，流式回复已完成。')).toBeInTheDocument())
    expect(screen.getByText('已调用 1 项原生能力')).toBeInTheDocument()

    // Open sessions drawer and create a new session
    fireEvent.click(screen.getByLabelText('会话列表'))
    expect(screen.getByText(/对话会话/)).toBeInTheDocument()
    fireEvent.click(screen.getByText('新建会话'))
    expect(screen.getByText('今天想一起做点什么？')).toBeInTheDocument()

    // Switch back to the first session and verify history persisted
    fireEvent.click(screen.getByLabelText('会话列表'))
    fireEvent.click(screen.getByText('帮我查一下记忆'))
    expect(screen.getByText('你好，流式回复已完成。')).toBeInTheDocument()
  })
})
