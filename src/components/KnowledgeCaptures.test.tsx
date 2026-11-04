import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { CaptureReader, CaptureShelf } from './KnowledgeCaptures'

const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn(), addKnowledgeCapturesListener: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
const audio = { id: 'audio', createdAt: 4, generation: 'given', text: '[原件]', source: 'original', mime: 'audio/wav', displayName: '会议.wav' }

it('在正文完成时响应原生事件，按续页位置翻阅，删除后撤下已读正文', async () => {
  let notify = () => {}
  let phase = 'queued'
  const remove = vi.fn()
  bridge.addKnowledgeCapturesListener.mockImplementation((listener: () => void) => { notify = listener; return Promise.resolve({ remove }) })
  bridge.companionDashboard.mockImplementation((request: { id: string; generation: string; offset: number }) => {
    expect(request.id).toBe(audio.id)
    expect(request.generation).toBe(audio.generation)
    if (phase === 'deleted') return Promise.resolve({ missing: true })
    return Promise.resolve({ row: { ...audio, parseState: phase }, items: phase === 'queued' ? [] : [{ id: 'p', text: request.offset === 0 ? '第一段转写' : '末段转写' }],
      nextOffset: phase === 'complete' && request.offset === 0 ? 9 : null })
  })
  const view = render(<CaptureReader reference={audio} onClose={vi.fn()} manage={vi.fn()} />)
  await screen.findByText('原件已保存。后台解析完成后，正文会显示在这里。')
  phase = 'complete'
  act(() => notify())
  await screen.findByText('第一段转写')
  fireEvent.click(screen.getByText('下一页正文'))
  await screen.findByText('末段转写')
  fireEvent.click(screen.getByText('上一页正文'))
  await screen.findByText('第一段转写')
  phase = 'deleted'
  act(() => notify())
  await screen.findByText('这份资料已删除或替换。')
  expect(screen.queryByText('第一段转写')).toBeNull()
  view.unmount()
  await waitFor(() => expect(remove).toHaveBeenCalled())
})

it('最近投放默认展示两项，展开后可直接打开对应原件', async () => {
  bridge.addKnowledgeCapturesListener.mockResolvedValue({ remove: vi.fn() })
  const cards = ['外婆的录音', '会议截图', '小红书摘录'].map((name, i) => ({ ...audio, id: String(i), displayName: name }))
  bridge.companionDashboard.mockResolvedValue({items: cards})
  const open = vi.fn()
  render(<CaptureShelf revision={0} open={open} />)
  await screen.findByText('外婆的录音')
  expect(screen.queryByText('小红书摘录')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: '查看全部 3' }))
  fireEvent.click(screen.getByText('小红书摘录'))
  expect(open).toHaveBeenCalledWith(cards[2])
})
