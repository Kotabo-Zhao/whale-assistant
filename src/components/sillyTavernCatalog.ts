export const OFFICIAL_CATALOG = 'https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/index.json'
const OFFICIAL_ASSETS = 'https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/assets/character/'
const RAW_ROOT = 'https://raw.githubusercontent.com/SillyTavern/SillyTavern-Content/main/'
const CDN_ROOT = 'https://cdn.jsdelivr.net/gh/SillyTavern/SillyTavern-Content@main/'
let preferCdn = false
export type CatalogCard = { id: string; name: string; description: string; url: string }

async function fetchOfficial(url: string): Promise<Response> {
  if (!url.startsWith(RAW_ROOT)) throw new Error('资源来源无效')
  const mirror = CDN_ROOT + url.slice(RAW_ROOT.length)
  for (const candidate of preferCdn ? [mirror, url] : [url, mirror]) {
    try {
      const response = await fetch(candidate, { signal: AbortSignal.timeout(5000) })
      if (response.ok) { preferCdn = candidate === mirror; return response }
    } catch { /* Try the official repository's CDN distribution. */ }
  }
  throw new Error('无法连接官方角色资源')
}

export async function loadOfficialCards(): Promise<CatalogCard[]> {
  const response = await fetchOfficial(OFFICIAL_CATALOG)
  const list: unknown = await response.json()
  if (!Array.isArray(list)) throw new Error('官方目录格式无效')
  return list.filter((row): row is CatalogCard & { type: string } =>
    row && row.type === 'character' && typeof row.id === 'string' && typeof row.name === 'string' &&
    typeof row.description === 'string' && typeof row.url === 'string' && row.url.startsWith(OFFICIAL_ASSETS))
}

/** SillyTavern PNG cards use a base64-encoded JSON payload in a chara or ccv3 tEXt chunk. */
export function readPngCard(bytes: Uint8Array): Record<string, unknown> {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10]
  if (bytes.length < 20 || !signature.every((v, i) => bytes[i] === v)) throw new Error('不是 PNG 角色卡')
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let position = 8
  while (position + 12 <= bytes.length) {
    const size = view.getUint32(position); const end = position + 12 + size
    if (end > bytes.length) throw new Error('PNG 数据不完整')
    const type = new TextDecoder().decode(bytes.subarray(position + 4, position + 8))
    if (type === 'tEXt') {
      const content = bytes.subarray(position + 8, position + 8 + size)
      const zero = content.indexOf(0)
      const key = new TextDecoder().decode(content.subarray(0, zero))
      if (zero > 0 && (key === 'chara' || key === 'ccv3')) {
        const encoded = new TextDecoder().decode(content.subarray(zero + 1))
        const data = Uint8Array.from(atob(encoded), ch => ch.charCodeAt(0))
        const parsed: unknown = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data))
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('角色卡格式无效')
        return parsed as Record<string, unknown>
      }
    }
    position = end
  }
  throw new Error('图片中没有角色卡数据')
}

export async function downloadOfficialCard(card: CatalogCard): Promise<Record<string, unknown>> {
  if (!card.url.startsWith(OFFICIAL_ASSETS)) throw new Error('角色卡来源无效')
  const response = await fetchOfficial(card.url)
  const data = new Uint8Array(await response.arrayBuffer())
  if (data.length > 16 * 1024 * 1024) throw new Error('角色卡文件过大')
  return readPngCard(data)
}
