import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { WhaleChatScreen } from './WhaleChatScreen'
import type { RuntimeBridge } from '../platform/types'

describe('WhaleChatScreen（无 Harness 原生直连对话主界面）', () => {
  it('展示零 Harness 入口的欢迎态，并支持点击灵感卡片直接调用原生 Agent', async () => {
    window.localStorage.clear()
    const whaleNativeChat = vi.fn().mockResolvedValue({
      reply: '已帮你梳理好今天的三项重点。',
      steps: [{ tool: 'whale_memory_search', label: '检索本地记忆', ok: true, durationMs: 14 }],
      model: 'deepseek-chat',
      elapsedMs: 620,
      mode: 'native_direct',
    })
    const onNavigate = vi.fn()
    const bridge = { whaleNativeChat } as unknown as RuntimeBridge

    render(<WhaleChatScreen bridge={bridge} onNavigate={onNavigate} />)

    expect(screen.getByText('今天想一起做点什么？')).toBeInTheDocument()
    fireEvent.click(screen.getByText('整理今日待办'))

    await waitFor(() => {
      expect(screen.getByText('已帮你梳理好今天的三项重点。')).toBeInTheDocument()
    })
    expect(whaleNativeChat).toHaveBeenCalledTimes(1)
    expect(screen.getByText('已调用 1 项原生能力')).toBeInTheDocument()
  })
})
