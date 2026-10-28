import { useEffect, useMemo, useRef, useState } from 'react'
import { t, useLanguage } from '../i18n'

import { fetchCatalog, type Entry } from './marketCatalog'

export function MobileMarket({ locked, onInstall }: { locked: boolean; onInstall: (name: string) => void }) {
  useLanguage()
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'failed'>('idle')
  const request = useRef<AbortController | null>(null)
  useEffect(() => () => { request.current?.abort(); request.current = null }, [])
  const visible = useMemo(() => {
    const term = query.trim().toLowerCase()
    return (entries ?? []).filter(item => !term || `${item.name} ${item.npm} ${item.description}`.toLowerCase().includes(term)).slice(0, 30)
  }, [entries, query])
  const load = async () => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setStatus('loading')
    const timeout = window.setTimeout(() => controller.abort(), 30000)
    try {
      const next = await fetchCatalog(controller.signal)
      if (request.current !== controller) return
      setEntries(next); setStatus('ready')
    } catch {
      if (request.current === controller) setStatus('failed')
    } finally { window.clearTimeout(timeout) }
  }
  return <section className="mobile-market" aria-labelledby="mobile-market-title">
    <h2 id="mobile-market-title">{t('手机插件目录')}</h2>
    <p>{t('浏览社区目录中的 npm 插件。安装由手机插件管理器处理，不运行安装脚本；是否能在安卓正常使用仍需验证。')}</p>
    <button className="button button-secondary compact-button" type="button" disabled={status === 'loading'} onClick={() => { void load() }}>
      {status === 'loading' ? t('正在获取目录…') : entries ? t('刷新目录') : t('加载插件目录')}
    </button>
    {status === 'failed' && <p role="alert">{t('目录获取失败，请检查网络后重试。')}</p>}
    {entries && <>
      <label className="mobile-market-search">{t('搜索目录')}<input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder={t('搜索插件名称或包名')} /></label>
      <p className="plugin-hint">{t('目录中有 {0} 个 npm 插件，最多显示前 30 个结果。', entries.length)}{locked ? t('请先停止运行环境再安装。') : ''}</p>
      {visible.length === 0 ? <p>{t('没有匹配的插件。')}</p> : <ul className="mobile-market-list">{visible.map(item => <li key={item.npm}>
        <div><strong>{item.name}</strong><code>{item.npm}</code>{item.description && <p>{item.description}</p>}</div>
        <button className="button button-secondary compact-button" type="button" disabled={locked} onClick={() => onInstall(item.npm)}>{t('安装')}</button>
      </li>)}</ul>}
    </>}
  </section>
}
