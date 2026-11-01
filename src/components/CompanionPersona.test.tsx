import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionPersona } from './CompanionPersona'

it('保存新角色并允许启用，导入时不覆盖已有角色', async () => {
  const initial = { id: 'default', name: '鲸鱼娘', description: '', personality: '', scenario: '', examples: '', greeting: '' }
  let cards = [initial]
  bridge.companionDashboard.mockImplementation((request: { operation: string; card?: typeof initial }) => {
    if (request.operation === 'personaList') return Promise.resolve({ cards, activeId: 'default' })
    if (request.operation === 'personaSave') { const card = { ...request.card!, id: 'saved' }; cards = [...cards, card]; return Promise.resolve({ ok: true, card }) }
    return Promise.resolve({ ok: true })
  })
  render(<CompanionPersona onBack={() => {}} />)
  await screen.findByRole('button', { name: /鲸鱼娘/ })
  fireEvent.click(screen.getByRole('button', { name: /新建/ }))
  fireEvent.change(screen.getByLabelText('角色名'), { target: { value: '小鲸' } })
  fireEvent.change(screen.getByLabelText('性格与说话方式'), { target: { value: '自然温柔' } })
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({ operation: 'personaSave', card: expect.objectContaining({ name: '小鲸', personality: '自然温柔' }) })))
  expect(await screen.findByRole('button', { name: '启用此角色' })).toBeInTheDocument()
})

it('导入角色卡附加指引和世界书并保存', async () => {
  const initial = { id: 'default', name: '鲸鱼娘', description: '', personality: '', scenario: '', examples: '', greeting: '' }
  bridge.companionDashboard.mockImplementation((request: { operation: string; card?: typeof initial }) => request.operation === 'personaList'
    ? Promise.resolve({ cards: [initial], activeId: 'default' })
    : Promise.resolve({ ok: true, card: { ...request.card, id: 'saved' } }))
  render(<CompanionPersona onBack={() => {}} />)
  await screen.findByRole('button', { name: /鲸鱼娘/ })
  fireEvent.click(screen.getByRole('button', { name: '导入导出' }))
  fireEvent.change(screen.getByPlaceholderText('粘贴角色卡 JSON'), { target: { value: JSON.stringify({ data: { name: '小鲸', system_prompt: '保持风格', post_history_instructions: '继续扮演', character_book: { entries: [{ keys: ['灯塔'], content: '海边' }] } } }) } })
  fireEvent.click(screen.getByRole('button', { name: '导入 JSON 草稿' }))
  fireEvent.click(screen.getByRole('button', { name: '保存' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({ operation: 'personaSave', card: expect.objectContaining({ name: '小鲸', systemPrompt: '保持风格', postHistory: '继续扮演', worldBook: expect.stringContaining('灯塔') }) })))
})
