import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { TaskStarter } from './TaskStarter'

const { insertTaskDraft } = vi.hoisted(() => ({ insertTaskDraft: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: { insertTaskDraft } }))

it('选择任务后展示对应示例，切换时更新草稿', () => {
  render(<TaskStarter />)
  fireEvent.click(screen.getByRole('button', { name: /整理文件/ }))
  expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: '任务示例：编辑后放入对话' }).value).toContain('不要直接删除或覆盖')
  fireEvent.click(screen.getByRole('button', { name: /总结当前页面/ }))
  expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: '任务示例：编辑后放入对话' }).value).toContain('如果页面读不到')
})

it('核对后的任务交给对话草稿，失败时保留复制入口', async () => {
  insertTaskDraft.mockRejectedValueOnce(new Error('not ready'))
  render(<TaskStarter />)
  fireEvent.click(screen.getByRole('button', { name: /整理文件/ }))
  const input = screen.getByRole<HTMLTextAreaElement>('textbox', { name: '任务示例：编辑后放入对话' })
  fireEvent.change(input, { target: { value: '请整理我选择的文件' } })
  fireEvent.click(screen.getByRole('button', { name: '放入对话草稿' }))
  await waitFor(() => expect(insertTaskDraft).toHaveBeenCalledWith('请整理我选择的文件'))
  expect(await screen.findByText('对话不可用，请复制任务后粘贴')).toBeVisible()
  expect(input.value).toBe('请整理我选择的文件')
  expect(screen.getByRole('button', { name: '复制任务' })).toBeEnabled()
})
