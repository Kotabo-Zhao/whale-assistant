import { useEffect, useRef, useState } from 'react'
import { ArrowUpRight, ChevronRight, Plus, Search, Wrench } from 'lucide-react'
import type { RuntimeBridge } from '../platform/types'
import './AuthoredTools.css'

type Tool = { id: string; title: string; revision: string }
const isTool = (item: unknown): item is Tool => {
  if (!item || typeof item !== 'object') return false
  const value = item as Partial<Tool>
  return typeof value.id === 'string' && /^[a-z][a-z0-9-]{0,47}$/.test(value.id) &&
    typeof value.title === 'string' && typeof value.revision === 'string' && /^[a-f0-9]{64}$/.test(value.revision)
}

export function AuthoredTools({ bridge, create = false }: { bridge: RuntimeBridge; create?: boolean }) {
  const [tools, setTools] = useState<Tool[]>([])
  const [expanded, setExpanded] = useState(false)
  const [message, setMessage] = useState('')
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [retry, setRetry] = useState(0)
  const [query, setQuery] = useState('')
  const [opening, setOpening] = useState<string | null>(null)
  const action = useRef(false)
  const mounted = useRef(false)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  useEffect(() => {
    let live = true
    let version = 0
    const refresh = async () => {
      if (document.hidden) return
      const request = ++version
      setLoadState('loading')
      try {
        const result = await bridge.companionDashboard({ operation: 'featureTools' })
        if (!Array.isArray(result.items) || !result.items.every(isTool)) throw new Error('invalid tools')
        if (live && request === version) { setTools(result.items); setLoadState('ready') }
      } catch { if (live && request === version) setLoadState('failed') }
    }
    const onChange = () => { void refresh() }
    const listener = bridge.addFeatureToolsListener?.(onChange).catch(() => null)
    document.addEventListener('visibilitychange', onChange)
    void refresh()
    return () => { live = false; document.removeEventListener('visibilitychange', onChange); void listener?.then(handle => handle?.remove()).catch(() => {}) }
  }, [bridge, retry])
  const open = async (target: Tool | 'create' | 'library') => {
    if (action.current) return
    action.current = true
    setOpening(typeof target === 'string' ? target : target.id); setMessage('')
    try {
      if (target === 'create') await bridge.insertTaskDraft('请为鲸鱼助手创建一个新功能。我希望它能：')
      else await bridge.companionDashboard(target === 'library' ? { operation: 'featureLibrary' } : { operation: 'featureOpen', id: target.id, revision: target.revision })
    } catch { if (mounted.current) setMessage(target === 'create' ? '无法进入创作对话，请重试。' : '工具未能打开，请重试。') }
    finally { action.current = false; if (mounted.current) setOpening(null) }
  }
  const matches = tools.filter(tool => tool.title.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  const visible = create || expanded ? matches : matches.slice(0, 4)
  return <section className={'authored-tools' + (create ? ' authored-tools-full' : '')} aria-label="我的工具">
    {create && <div className="tool-creation-entry">
      <div><span className="tool-creation-label">创造模式</span><h2>让鲸鱼学会你需要的功能</h2><p>在对话中描述需求，生成后到功能工坊预览、启用，再从 App 或桌宠打开。</p></div>
      <div className="tool-creation-actions"><button type="button" className="button button-primary" disabled={opening !== null} onClick={() => { void open('create') }}><Plus size={18}/>{opening === 'create' ? '正在打开…' : '去对话创作'}</button>
        <button type="button" className="button button-secondary" disabled={opening !== null} onClick={() => { void open('library') }}>功能工坊<ChevronRight size={16}/></button></div>
    </div>}
    <div className="authored-tools-heading"><h3><Wrench size={17} aria-hidden="true" />我的工具{loadState === 'ready' && <span className="tool-count">{tools.length}</span>}</h3>
      {!create && <button type="button" disabled={opening !== null} onClick={() => { void open('library') }}>功能工坊 <ChevronRight size={15} aria-hidden="true" /></button>}</div>
    {create && tools.length > 6 && <label className="tool-search"><Search size={17} aria-hidden="true"/><input type="search" aria-label="查找我的工具" placeholder="查找工具" value={query} onChange={event => setQuery(event.target.value)} /></label>}
    {loadState === 'loading' && <p role="status">正在读取工具…</p>}
    {loadState === 'failed' && <div className="tool-list-error" role="status"><p>工具列表暂时不可用。{tools.length > 0 ? '下方保留上次结果。' : ''}</p><button className="button button-secondary" type="button" onClick={() => setRetry(value => value + 1)}>重新读取工具</button></div>}
    {tools.length > 0 ? <div className="authored-tool-links">{visible.map(tool =>
      <button type="button" className="authored-tool-card" key={tool.id} disabled={opening !== null} onClick={() => { void open(tool) }}>
        <span>{tool.title}</span>{create && <ArrowUpRight size={17} aria-hidden="true"/>}{opening === tool.id && <small role="status">正在打开…</small>}</button>)}
      {!create && tools.length > 4 && <button type="button" className="authored-tool-more" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
        {expanded ? '收起' : `显示其余 ${tools.length - 4} 项`}</button>}
      {query && matches.length === 0 && <p>没有匹配的工具。</p>}</div> : loadState === 'ready' &&
      <div className="tool-empty"><Wrench size={25} aria-hidden="true"/><p>还没有启用的工具。<br/>在功能工坊启用后，会出现在这里。</p></div>}
    {message && <p className="tool-action-message" role="status">{message}</p>}
  </section>
}
