import { fireEvent, render, screen } from '@testing-library/react'
import { expect, it } from 'vitest'
import { TaskStarter } from './TaskStarter'

it('选择任务后展示对应示例，切换时更新草稿', () => {
  render(<TaskStarter />)
  fireEvent.click(screen.getByRole('button', { name: /整理文件/ }))
  expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: '任务示例：可编辑后复制到对话' }).value).toContain('不要直接删除或覆盖')
  fireEvent.click(screen.getByRole('button', { name: /总结当前页面/ }))
  expect(screen.getByRole<HTMLTextAreaElement>('textbox', { name: '任务示例：可编辑后复制到对话' }).value).toContain('如果页面读不到')
})
