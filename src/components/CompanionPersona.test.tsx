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
