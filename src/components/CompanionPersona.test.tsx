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
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({
    operation: 'personaSave', card: expect.objectContaining({ name: '小鲸', personality: '自然温柔' }) as unknown,
  })))
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
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({
    operation: 'personaSave', card: expect.objectContaining({ name: '小鲸', systemPrompt: '保持风格',
      postHistory: '继续扮演', worldBook: expect.stringContaining('灯塔') as unknown }) as unknown,
  })))
})

it('展示情感与性格养成雷达并支持记录默契里程碑', async () => {
  const initial = { id: 'default', name: '鲸鱼娘', description: '', personality: '', scenario: '', examples: '', greeting: '' }
  const emotion = {
    personaId: 'default',
    mood: 'HAPPY',
    moodLabel: '开心雀跃',
    energy: 88,
    bondLevel: 4,
    interactions: 12,
    traits: { warmth: 84, playfulness: 70, proactivity: 74, directness: 66, empathy: 80, trust: 42 },
    growthNotes: ['主人偏好干脆利落、直给结论的沟通风格'],
  }
  bridge.companionDashboard.mockImplementation((request: { operation: string; note?: string }) => {
    if (request.operation === 'personaList') return Promise.resolve({ cards: [initial], activeId: 'default', emotions: { default: emotion } })
    if (request.operation === 'personaEmotionNote') {
      return Promise.resolve({
        ok: true,
        emotion: { ...emotion, growthNotes: [...emotion.growthNotes, request.note ?? ''] },
      })
    }
    return Promise.resolve({ ok: true })
  })
  render(<CompanionPersona onBack={() => {}} />)
  expect(await screen.findByText('情感与性格养成')).toBeInTheDocument()
  expect(screen.getByText('开心雀跃')).toBeInTheDocument()
  expect(screen.getByText('羁绊 Lv.4')).toBeInTheDocument()
  expect(screen.getByText('已互动 12 轮')).toBeInTheDocument()
  expect(screen.getByRole('img', { name: '六维性格成长雷达图' })).toBeInTheDocument()
  fireEvent.change(screen.getByPlaceholderText(/手动记一条默契/), { target: { value: '深夜加班时轻声鼓励' } })
  fireEvent.click(screen.getByRole('button', { name: '记录默契' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({
    operation: 'personaEmotionNote',
    id: 'default',
    note: '深夜加班时轻声鼓励',
  })))
  expect(await screen.findByText('深夜加班时轻声鼓励')).toBeInTheDocument()
})
