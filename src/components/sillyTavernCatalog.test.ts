import { afterEach, expect, it, vi } from 'vitest'
import { downloadOfficialCard, loadOfficialCards, readPngCard } from './sillyTavernCatalog'

const png = () => {
  const json = JSON.stringify({ spec: 'chara_card_v2', data: { name: '鲸鱼', personality: '温柔' } })
  const encoded = btoa(Array.from(new TextEncoder().encode(json), value => String.fromCharCode(value)).join(''))
  const body = new TextEncoder().encode(`chara\0${encoded}`)
  const bytes = new Uint8Array(8 + 12 + body.length)
  bytes.set([137, 80, 78, 71, 13, 10, 26, 10]); new DataView(bytes.buffer).setUint32(8, body.length)
  bytes.set(new TextEncoder().encode('tEXt'), 12); bytes.set(body, 16)
  return bytes
}
afterEach(() => vi.unstubAllGlobals())
it('读取 PNG 中的 Unicode 角色卡，并拒绝损坏文件', () => {
  expect((readPngCard(png()).data as { name: string }).name).toBe('鲸鱼')
  expect(() => readPngCard(new Uint8Array([1, 2, 3]))).toThrow()
})
it('只从官方角色目录下载角色卡', async () => {
  const url = 'https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/assets/character/test.png'
  vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValueOnce(Response.json([{ type: 'character', id: '1', name: '测试', description: '简介', url }, { type: 'character', id: '2', name: '外部', description: '', url: 'https://example.com/a.png' }]))
    .mockResolvedValueOnce(new Response(png())))
  const cards = await loadOfficialCards()
  expect(cards).toHaveLength(1)
  expect((await downloadOfficialCard(cards[0])).spec).toBe('chara_card_v2')
})
it('官方 GitHub 不可用时使用同一仓库的 CDN', async () => {
  const fetcher = vi.fn<typeof fetch>().mockRejectedValueOnce(new Error('unavailable')).mockResolvedValueOnce(Response.json([]))
  vi.stubGlobal('fetch', fetcher)
  expect(await loadOfficialCards()).toEqual([])
  expect(fetcher.mock.calls[1][0]).toContain('cdn.jsdelivr.net/gh/SillyTavern/SillyTavern-Content@main/')
})
