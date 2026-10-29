const CATALOG_URL = 'https://awesome-dsh-plugin.com/plugins.json'
const MAX_BYTES = 16 * 1024 * 1024
import { validatePluginRequest } from '../platform/plugins'

const PACKAGE = /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/

export type Entry = { name: string; npm: string | null; description: string; category?: string; categoryLabel?: string; sourceUrl?: string; downloads?: number; downloadsStart?: string; downloadsEnd?: string }

/** 目录是第三方数据；只呈现有限文本和经校验的 npm 包名。 */
export function parseMarketCatalog(value: unknown): Entry[] {
  if (!value || typeof value !== 'object' || !('plugins' in value) || !Array.isArray(value.plugins)) throw new Error('目录格式无效')
  const entries: Entry[] = []
  const seen = new Set<string>()
  const categories = 'categories' in value && value.categories && typeof value.categories === 'object' && !Array.isArray(value.categories)
    ? value.categories as Record<string, unknown> : {}
  for (const raw of value.plugins.slice(0, 20000)) {
    if (!raw || typeof raw !== 'object') continue
    const row = raw as Record<string, unknown>
    const npm = typeof row.npm === 'string' && row.npm.length <= 214 && PACKAGE.test(row.npm) ? row.npm : null
    // Non-npm entries remain browsable only with a well-formed public GitHub project URL.
    // Explicitly malformed package names are rejected rather than treated as installable.
    if (row.npm != null && npm === null) continue
    let sourceUrl: string | undefined
    try {
      if (typeof row.url === 'string') {
        const source = new URL(row.url)
        if (source.protocol === 'https:' && source.hostname === 'github.com' && !source.username && !source.password && !source.port && /^\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/?$/.test(source.pathname) && !source.search && !source.hash) sourceUrl = source.href
      }
    } catch { /* Ignore malformed untrusted source URLs. */ }
    const key = npm ?? sourceUrl
    if (!key || seen.has(key)) continue
    const description = row.description && typeof row.description === 'object' ? row.description as Record<string, unknown> : {}
    const category = typeof row.category === 'string' && /^[a-z0-9_-]{1,40}$/.test(row.category) ? row.category : undefined
    const label = category && categories[category] && typeof categories[category] === 'object' ? categories[category] as Record<string, unknown> : null
    const categoryLabel = label && typeof label.zh === 'string' ? label.zh.slice(0, 60) : category
    entries.push({ npm, name: typeof row.name === 'string' ? row.name.slice(0, 120) : npm ?? '未命名插件',
      description: (typeof description.zh === 'string' ? description.zh : typeof description.en === 'string' ? description.en : '').slice(0, 240),
      ...(category ? { category, categoryLabel } : {}), ...(!npm && sourceUrl ? { sourceUrl } : {}),
      ...(typeof row.downloads === 'number' && Number.isSafeInteger(row.downloads) && row.downloads >= 0 ? { downloads: row.downloads } : {}),
      ...(typeof row.downloadsStart === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(row.downloadsStart) ? { downloadsStart: row.downloadsStart } : {}),
      ...(typeof row.downloadsEnd === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(row.downloadsEnd) ? { downloadsEnd: row.downloadsEnd } : {}) })
    seen.add(key)
  }
  return entries
}

/** JSON parsing can be expensive on a large catalog; use a worker when the WebView supports it. */
export function parseCatalogData(data: Uint8Array): Entry[] {
  return parseMarketCatalog(JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data)))
}

export async function parseCatalogOffThread(data: Uint8Array, signal: AbortSignal): Promise<Entry[]> {
  if (signal.aborted) throw new Error('目录读取已取消')
  if (typeof Worker === 'undefined') return parseCatalogData(data)
  let worker: Worker
  try { worker = new Worker(new URL('./marketCatalog.worker.ts', import.meta.url), { type: 'module' }) }
  catch { return parseCatalogData(data) }
  return new Promise<Entry[]>((resolve, reject) => {
    let finished = false
    const finish = (value: Entry[] | null, error?: Error) => {
      if (finished) return
      finished = true
      signal.removeEventListener('abort', abort)
      worker.terminate()
      if (error) reject(error)
      else resolve(value ?? [])
    }
    const abort = () => finish(null, new Error('目录读取已取消'))
    signal.addEventListener('abort', abort, { once: true })
    worker.onmessage = event => {
      if (finished) return
      const message: unknown = event.data
      if (!message || typeof message !== 'object' || !('ok' in message) || message.ok !== true || !('entries' in message) || !Array.isArray(message.entries)) {
        finish(null, new Error('目录格式无效')); return
      }
      finish(message.entries as Entry[])
    }
    worker.onerror = () => {
      if (finished) return
      if (signal.aborted) { abort(); return }
      try { finish(parseCatalogData(data)) } catch { finish(null, new Error('目录格式无效')) }
    }
    try {
      // Keep the original for a fallback if a WebView cannot start workers.
      const copy = data.slice().buffer
      worker.postMessage(copy, [copy])
    } catch {
      try { finish(parseCatalogData(data)) } catch { finish(null, new Error('目录格式无效')) }
    }
    if (signal.aborted) abort()
  })
}

export async function fetchCatalog(signal: AbortSignal, onProgress?: (bytes: number) => void): Promise<Entry[]> {
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
      onProgress?.(size)
      if (size > MAX_BYTES) { await reader.cancel(); throw new Error('目录过大') }
      chunks.push(chunk.value)
    }
  } finally { reader.releaseLock() }
  const data = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.byteLength }
  return parseCatalogOffThread(data, signal)
}


export type PackagePreview = { version: string; os: string; cpu: string; engines: string; harness: string; dependencies: string; optionalDependencies: string; peerDependencies: string; scripts: string }

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
  const ranges = (item: unknown) => {
    if (item === undefined) return '未声明'
    if (!item || typeof item !== 'object' || Array.isArray(item)) return '声明格式无法识别'
    const entries = Object.entries(item as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b))
    if (entries.some(([key, value]) => key.length > 214 || typeof value !== 'string' || value.length > 256)) return '声明格式无法识别'
    return entries.length ? entries.slice(0, 12).map(([key, value]) => `${key}: ${String(value)}`).join(', ') + (entries.length > 12 ? ` 等 ${entries.length} 项` : '') : '无'
  }
  const harness = (() => {
    if (row.dsh === undefined) return '未声明'
    if (!row.dsh || typeof row.dsh !== 'object' || Array.isArray(row.dsh)) return '声明格式无法识别'
    const pluginEngines = (row.dsh as Record<string, unknown>).engines
    if (pluginEngines === undefined) return '未声明'
    if (!pluginEngines || typeof pluginEngines !== 'object' || Array.isArray(pluginEngines)) return '声明格式无法识别'
    const range = (pluginEngines as Record<string, unknown>).dsh
    if (range === undefined) return '未声明'
    return typeof range === 'string' && range.length > 0 && range.length <= 256 ? range : '声明格式无法识别'
  })()
  return { version: row.version, os: list(row.os), cpu: list(row.cpu), engines, harness,
    dependencies: ranges(row.dependencies), optionalDependencies: ranges(row.optionalDependencies),
    peerDependencies: ranges(row.peerDependencies), scripts: map(row.scripts) }
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
