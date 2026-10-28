const CATALOG_URL = 'https://awesome-dsh-plugin.com/plugins.json'
const MAX_BYTES = 16 * 1024 * 1024
const PACKAGE = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/

export type Entry = { name: string; npm: string; description: string }

/** 目录是第三方数据；只呈现有限文本和经校验的 npm 包名。 */
export function parseMarketCatalog(value: unknown): Entry[] {
  if (!value || typeof value !== 'object' || !('plugins' in value) || !Array.isArray(value.plugins)) throw new Error('目录格式无效')
  const entries: Entry[] = []
  const seen = new Set<string>()
  for (const raw of value.plugins.slice(0, 20000)) {
    if (!raw || typeof raw !== 'object') continue
    const row = raw as Record<string, unknown>
    const npm = typeof row.npm === 'string' ? row.npm : ''
    if (npm.length > 214 || !PACKAGE.test(npm) || seen.has(npm)) continue
    const description = row.description && typeof row.description === 'object' ? row.description as Record<string, unknown> : {}
    entries.push({ npm, name: typeof row.name === 'string' ? row.name.slice(0, 120) : npm,
      description: (typeof description.zh === 'string' ? description.zh : typeof description.en === 'string' ? description.en : '').slice(0, 240) })
    seen.add(npm)
  }
  return entries
}

export async function fetchCatalog(signal: AbortSignal): Promise<Entry[]> {
  const response = await fetch(CATALOG_URL, { signal, credentials: 'omit', redirect: 'error' })
  if (!response.ok || !response.body) throw new Error('目录不可用')
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > MAX_BYTES) { await reader.cancel(); throw new Error('目录过大') }
      chunks.push(chunk.value)
    }
  } finally { reader.releaseLock() }
  const data = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength }
  return parseMarketCatalog(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data)))
}

