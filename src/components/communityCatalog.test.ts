import { afterEach, expect, it, vi } from 'vitest'
import { downloadCommunityCard, searchCommunity } from './communityCatalog'

afterEach(() => vi.unstubAllGlobals())
it('搜索第三方目录并将角色定义转换为可编辑草稿', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce({ ok: true, json: async () => ({ data: { nodes: [{ name: '小鲸', fullPath: 'creator/whale', tagline: '温柔' }, { name: '无效', fullPath: '../unsafe' }] } }) })
    .mockResolvedValueOnce({ ok: true, json: async () => ({ node: { definition: { name: '小鲸', description: '助手', personality: '温柔', first_message: '你好', example_dialogs: '你好呀', system_prompt: '角色指引', post_history_instructions: '保持风格', embedded_lorebook: { entries: [] } } } }) })
  vi.stubGlobal('fetch', fetcher)
  const cards = await searchCommunity('whale', 1)
  expect(cards).toHaveLength(1)
  expect((await downloadCommunityCard(cards[0])).data).toMatchObject({ name: '小鲸', first_mes: '你好', mes_example: '你好呀', system_prompt: '角色指引', post_history_instructions: '保持风格', character_book: { entries: [] } })
  expect(fetcher.mock.calls[0][0].toString()).toContain('search=whale')
})
