import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import type { RuntimeBridge } from '../platform/types'
import { AuthoredTools } from './AuthoredTools'
const tool = { id: 'reading-card', title: '阅读摘录卡', revision: 'a'.repeat(64) }
function bridge() {
  return { companionDashboard: vi.fn<(request: Record<string, unknown>) => Promise<Record<string, unknown>>>().mockResolvedValue({ items: [] }), insertTaskDraft: vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined), addFeatureToolsListener: vi.fn<(listener: () => void) => Promise<{ remove: () => void }>>().mockResolvedValue({ remove: vi.fn() }) }
}
it('首次等待和读取失败不冒充空工具列表，重试后显示真实工具', async () => {
  const native = bridge()
  let reject!: (error: Error) => void
  native.companionDashboard.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
  render(<AuthoredTools bridge={native as unknown as RuntimeBridge} create />)
  expect(screen.getByText('正在读取工具…')).toBeInTheDocument()
  expect(screen.queryByText(/还没有启用的工具/)).toBeNull()
  await act(async () => { reject(new Error('offline')); await Promise.resolve() })
  native.companionDashboard.mockResolvedValue({ items: [tool] })
  fireEvent.click(screen.getByRole('button', { name: '重新读取工具' }))
  expect(await screen.findByRole('button', { name: tool.title })).toBeInTheDocument()
})
it('损坏的工具列表给出可恢复错误，不把条目部分接受为正常列表', async () => {
  const native = bridge()
  native.companionDashboard.mockResolvedValue({ items: [tool, { ...tool, revision: 'invalid' }] })
  render(<AuthoredTools bridge={native as unknown as RuntimeBridge} />)
  expect(await screen.findByText('工具列表暂时不可用。')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: tool.title })).toBeNull()
})
it('打开在途时不会重复派发，结束后可继续打开且使用已启用修订', async () => {
  const native = bridge()
  let finish!: () => void
  native.companionDashboard.mockResolvedValue({ items: [tool] })
  render(<AuthoredTools bridge={native as unknown as RuntimeBridge} create />)
  const button = await screen.findByRole('button', { name: tool.title })
  native.companionDashboard.mockReturnValueOnce(new Promise(resolve => { finish = () => resolve({}) }))
  fireEvent.click(button); fireEvent.click(button)
  expect(native.companionDashboard.mock.calls.filter(([request]) => request.operation === 'featureOpen')).toHaveLength(1)
  expect(native.companionDashboard).toHaveBeenCalledWith({ operation: 'featureOpen', id: tool.id, revision: tool.revision })
  await act(async () => { finish(); await Promise.resolve() })
  expect(button).not.toBeDisabled()
})
it('工具事件更新不会抹掉打开失败提示，创作只写入对话草稿', async () => {
  const native = bridge()
  let notify = () => {}
  native.addFeatureToolsListener.mockImplementation(listener => { notify = listener; return Promise.resolve({ remove: vi.fn() }) })
  native.companionDashboard.mockResolvedValue({ items: [tool] })
  render(<AuthoredTools bridge={native as unknown as RuntimeBridge} create />)
  fireEvent.click(await screen.findByRole('button', { name: '去对话创作' }))
  await waitFor(() => expect(native.insertTaskDraft).toHaveBeenCalledWith('请为鲸鱼助手创建一个新功能。我希望它能：'))
  native.companionDashboard.mockRejectedValueOnce(new Error('unavailable'))
  fireEvent.click(await screen.findByRole('button', { name: tool.title }))
  await screen.findByText('工具未能打开，请重试。')
  await act(async () => { notify(); await Promise.resolve() })
  expect(screen.getByText('工具未能打开，请重试。')).toBeInTheDocument()
})
