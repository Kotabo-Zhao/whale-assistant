export type CommunityCard = { path: string; name: string; description: string }
const validPath = (path: string) => /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(path) && path.split('/').every(part => part !== '.' && part !== '..')

export async function searchCommunity(query: string, page: number): Promise<CommunityCard[]> {
  const url = new URL('https://api.chub.ai/search')
  url.searchParams.set('search', query.trim()); url.searchParams.set('first', '20')
  url.searchParams.set('page', String(page)); url.searchParams.set('sort', 'last_activity_at')
  const response = await fetch(url, { signal: AbortSignal.timeout(15000) })
  if (!response.ok) throw new Error('社区目录不可用')
  const result = await response.json() as { data?: { nodes?: unknown[] } }
  if (!Array.isArray(result.data?.nodes)) throw new Error('社区目录格式无效')
  return result.data.nodes.flatMap(value => {
    if (!value || typeof value !== 'object') return []
    const wrapper = value as Record<string, unknown>
    const node = (wrapper.node && typeof wrapper.node === 'object' ? wrapper.node : wrapper) as Record<string, unknown>
    if (typeof node.name !== 'string' || typeof node.fullPath !== 'string' || !validPath(node.fullPath)) return []
    return [{ path: node.fullPath, name: node.name, description: typeof node.tagline === 'string' ? node.tagline : '' }]
  })
}

export async function downloadCommunityCard(card: CommunityCard): Promise<Record<string, unknown>> {
  if (!validPath(card.path)) throw new Error('角色路径无效')
  const response = await fetch(`https://gateway.chub.ai/api/characters/${card.path}?full=true`, { signal: AbortSignal.timeout(15000) })
  if (!response.ok) throw new Error('社区角色下载失败')
  const result = await response.json() as { node?: { name?: unknown; definition?: Record<string, unknown> } }
  const definition = result.node?.definition
  if (!definition || typeof definition !== 'object') throw new Error('角色定义不可用')
  return { data: {
    name: definition.name ?? result.node?.name, description: definition.description,
    personality: definition.personality || definition.tavern_personality, scenario: definition.scenario,
    first_mes: definition.first_message, mes_example: definition.example_dialogs,
  } }
}
