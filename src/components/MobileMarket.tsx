import { useEffect, useMemo, useRef, useState } from 'react'
import { t, useLanguage } from '../i18n'

import { fetchCatalog, fetchPackagePreview, type Entry, type PackagePreview } from './marketCatalog'
import { validatePluginRequest } from '../platform/plugins'

const PAGE_SIZE = 30
const declaration = (value: string) => ['未声明', '声明格式无法识别', '无'].includes(value) ? t(value) : value

type InstallState = { name: string; busy: boolean; failed: boolean; notice: string; detail: string }
export function MobileMarket({ locked, onInstall, installState = null }: { locked: boolean; onInstall: (name: string) => void; installState?: InstallState | null }) {
  useLanguage()
  const [entries, setEntries] = useState<Entry[] | null>(null)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [review, setReview] = useState<Entry | null>(null)
  const [version, setVersion] = useState('')
  const [preview, setPreview] = useState<PackagePreview | null>(null)
  const [previewStatus, setPreviewStatus] = useState<'idle' | 'loading' | 'failed'>('idle')
  const previewRequest = useRef<AbortController | null>(null)
  const [status, setStatus] = useState<'idle' | 'loading' | 'ready' | 'failed'>('idle')
  const request = useRef<AbortController | null>(null)
  const reviewRegion = useRef<HTMLDivElement | null>(null)
  const resultsRegion = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (review) { reviewRegion.current?.scrollIntoView?.({ block: 'nearest' }); reviewRegion.current?.focus() }
  }, [review])
  useEffect(() => () => { request.current?.abort(); request.current = null; previewRequest.current?.abort(); previewRequest.current = null }, [])
  useEffect(() => { previewRequest.current?.abort(); previewRequest.current = null; setPreview(null); setPreviewStatus('idle') }, [review, version])
  const matches = useMemo(() => {
    const term = query.trim().toLowerCase()
    return (entries ?? []).filter(item => !term || `${item.name} ${item.npm} ${item.description}`.toLowerCase().includes(term))
  }, [entries, query])
  const pageCount = Math.ceil(matches.length / PAGE_SIZE)
  const currentPage = Math.min(page, Math.max(0, pageCount - 1))
  const visible = matches.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)
  const changePage = (next: number) => {
    setReview(null)
    setPage(next)
    resultsRegion.current?.scrollIntoView?.({ block: 'start' })
    resultsRegion.current?.focus()
  }
  const versionValid = !review || !version.trim() || (() => {
    try { validatePluginRequest({ operation: 'import', source: `${review.npm}@${version.trim()}` }); return true } catch { return false }
  })()
  const inspect = async () => {
    if (!review || !versionValid) return
    previewRequest.current?.abort()
    const controller = new AbortController()
    previewRequest.current = controller
    setPreview(null); setPreviewStatus('loading')
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    try {
      const result = await fetchPackagePreview(review.npm, version.trim(), controller.signal)
      if (previewRequest.current === controller) { setPreview(result); setPreviewStatus('idle') }
    } catch { if (previewRequest.current === controller) setPreviewStatus('failed') }
    finally { window.clearTimeout(timeout) }
  }
  const load = async () => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setReview(null)
    setPage(0)
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
    <p className="plugin-hint">{t('最多读取目录前 20000 条，跳过无效或重复包名。')}</p>
    <button className="button button-secondary compact-button" type="button" disabled={status === 'loading'} onClick={() => { void load() }}>
      {status === 'loading' ? t('正在获取目录…') : entries ? t('刷新目录') : t('加载插件目录')}
    </button>
    {status === 'failed' && <p role="alert">{t('目录获取失败，请检查网络后重试。')}</p>}
    {installState && <div className={`mobile-market-result ${installState.failed ? 'mobile-market-result-error' : ''}`}
      role={installState.failed ? 'alert' : 'status'} aria-live={installState.failed ? 'assertive' : 'polite'}>
      <strong>{t('插件安装')} · <code>{installState.name}</code></strong>
      <p>{installState.busy ? t('正在检查并安装，请稍候。') : t(installState.notice)}{!installState.busy && installState.detail && `（${installState.detail}）`}</p>
    </div>}
    {review && <div ref={reviewRegion} tabIndex={-1} className="mobile-market-review" role="region" aria-labelledby="mobile-market-review-title">
      <h3 id="mobile-market-review-title">{t('确认安装插件')}</h3>
      <p><strong>{review.name}</strong><br /><code>{review.npm}</code></p>
      <p>{t('来源：npm 社区包。版本将在下载时按当前运行环境选择；目录信息不代表已验证作者、权限或安卓兼容性。')}</p>
      <p>{t('安装脚本不会运行；安装后插件本身可能读取数据或联网。请先确认你信任此来源。')}</p>
      <label className="mobile-market-version">{t('指定版本（可选）')}
        <input value={version} onChange={event => setVersion(event.target.value)} placeholder="1.2.3" aria-invalid={!versionValid} />
      </label>
      <p className="plugin-hint">{t('留空自动选择；填写已知精确版本可尝试较旧版本，仍会检查兼容性。')}</p>
      {!versionValid && <p role="alert">{t('请输入完整版本号，例如 1.2.3。')}</p>}
      <button className="button button-secondary compact-button" type="button" disabled={!versionValid || previewStatus === 'loading'} onClick={() => { void inspect() }}>
        {previewStatus === 'loading' ? t('正在读取包信息…') : t('查看 npm 包声明')}
      </button>
      {previewStatus === 'failed' && <p role="alert">{t('无法读取包声明，请检查网络或版本后重试；仍可交给手机管理器检查。')}</p>}
      {preview && <div className="plugin-hint" role="status">
        <p>{t('npm 声明版本')}：<code>{preview.version}</code>{!version.trim() && ` · ${t('最新版本，自动安装可能选择其他版本')}`}</p>
        <p>OS: {declaration(preview.os)} · CPU: {declaration(preview.cpu)}</p>
        <p>{t('Harness 版本要求')}：{declaration(preview.harness)}</p>
        <p>{t('引擎声明')}：{declaration(preview.engines)}</p>
        <p>{t('直接依赖')}：{declaration(preview.dependencies)}</p>
        <p>{t('可选依赖')}：{declaration(preview.optionalDependencies)}</p>
        <p>{t('同伴依赖')}：{declaration(preview.peerDependencies)}</p>
        <p>{t('脚本名称')}：{declaration(preview.scripts)}</p>
        <p>{t('以上只是包作者声明，并非安全或安卓兼容认证；安装时会再次检查，不运行安装脚本。')}</p>
      </div>}
      {locked && <p role="status">{t('请先停止运行环境再安装。')}</p>}
      <div className="mobile-market-review-actions">
        <button className="button button-secondary compact-button" type="button" onClick={() => setReview(null)}>{t('取消')}</button>
        <button className="button button-primary compact-button" type="button" disabled={locked || !versionValid} onClick={() => {
          if (locked || !versionValid) return
          const name = version.trim() ? `${review.npm}@${version.trim()}` : review.npm
          setReview(null)
          onInstall(name)
        }}>{t('确认安装')}</button>
      </div>
    </div>}
    {entries && <>
      <label className="mobile-market-search">{t('搜索目录')}<input type="search" value={query} onChange={event => { setQuery(event.target.value); setPage(0); setReview(null) }} placeholder={t('搜索插件名称或包名')} /></label>
      <div ref={resultsRegion} tabIndex={-1}>
        <p className="plugin-hint" role="status" aria-live="polite">{matches.length === 0 ? t('没有匹配的插件。') : t('共 {0} 个结果，第 {1}/{2} 页。', matches.length, currentPage + 1, pageCount)}{locked ? t('请先停止运行环境再安装。') : ''}</p>
        {visible.length > 0 && <ul className="mobile-market-list">{visible.map(item => <li key={item.npm}>
          <div><strong>{item.name}</strong><code>{item.npm}</code>{item.description && <p>{item.description}</p>}</div>
          <button className="button button-secondary compact-button" type="button" disabled={locked} onClick={() => { setVersion(''); setReview(item) }}>{t('安装')}</button>
        </li>)}</ul>}
      </div>
      {pageCount > 1 && <nav className="mobile-market-pagination" aria-label={t('插件目录分页')}>
        <button className="button button-secondary compact-button" type="button" disabled={currentPage === 0} onClick={() => changePage(currentPage - 1)}>{t('上一页')}</button>
        <button className="button button-secondary compact-button" type="button" disabled={currentPage + 1 >= pageCount} onClick={() => changePage(currentPage + 1)}>{t('下一页')}</button>
      </nav>}
    </>}
  </section>
}
