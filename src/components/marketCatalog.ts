const CATALOG_URL = 'https://awesome-dsh-plugin.com/plugins.json'
const MAX_BYTES = 16 * 1024 * 1024
import { validatePluginRequest } from '../platform/plugins'

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


export type PackagePreview = { version: string; os: string; cpu: string; engines: string; dependencies: string; scripts: string }

/** Untrusted registry declarations for display only. Missing fields are not proof of compatibility. */
export function parsePackagePreview(value: unknown, name: string, requestedVersion: string): PackagePreview {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('无效元数据')
  const row = value as Record<string, unknown>
  if (row.name !== name || typeof row.version !== 'string') throw new Error('包身份不匹配')
  validatePluginRequest({ operation: 'import', source: `${name}@${row.version}` })
  if (requestedVersion && row.version !== requestedVersion) throw new Error('版本不匹配')
  const list = (item: unknown) => Array.isArray(item) && item.length <= 30 && item.every(x => typeof x === 'string' && x.length <= 80)
    ? item.join(', ') || '未声明' : item === undefined ? '未声明' : '声明格式无法识别'
  const map = (item: unknown) => {
    if (item === undefined) return '未声明'
    if (!item || typeof item !== 'object' || Array.isArray(item)) return '声明格式无法识别'
    const keys = Object.keys(item as Record<string, unknown>).sort()
    if (keys.some(key => key.length > 214)) return '声明格式无法识别'
    return keys.length ? keys.slice(0, 12).join(', ') + (keys.length > 12 ? ` 等 ${keys.length} 项` : '') : '无'
  }
  const engines = (() => {
    if (row.engines === undefined) return '未声明'
    if (!row.engines || typeof row.engines !== 'object' || Array.isArray(row.engines)) return '声明格式无法识别'
    const entries = Object.entries(row.engines as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b))
    if (entries.some(([key, value]) => key.length > 80 || typeof value !== 'string' || value.length > 160)) return '声明格式无法识别'
    return entries.length ? entries.slice(0, 12).map(([key, value]) => `${key}: ${String(value)}`).join(', ') + (entries.length > 12 ? ` 等 ${entries.length} 项` : '') : '无'
  })()
  return { version: row.version, os: list(row.os), cpu: list(row.cpu), engines, dependencies: map(row.dependencies), scripts: map(row.scripts) }
}

export async function fetchPackagePreview(name: string, version: string, signal: AbortSignal): Promise<PackagePreview> {
  validatePluginRequest({ operation: 'import', source: version ? `${name}@${version}` : name })
  const response = await fetch(`https://registry.npmjs.org/${encodeURIComponent(name)}/${encodeURIComponent(version || 'latest')}`, { signal, credentials: 'omit', redirect: 'error' })
  if (!response.ok || !response.body) throw new Error('元数据不可用')
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let size = 0
  try {
    while (true) {
      const chunk = await reader.read()
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > 512 * 1024) { await reader.cancel(); throw new Error('元数据过大') }
      chunks.push(chunk.value)
    }
  } finally { reader.releaseLock() }
  const data = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength }
  return parsePackagePreview(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data)), name, version)
}
