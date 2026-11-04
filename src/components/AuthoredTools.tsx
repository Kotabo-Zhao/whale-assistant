import { useEffect, useState } from 'react'
import { ChevronRight, Wrench } from 'lucide-react'
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
  useEffect(() => {
    let live = true
    let version = 0
    const refresh = async () => {
      if (document.hidden || !bridge.companionDashboard) return
      const request = ++version
      try {
        const result = await bridge.companionDashboard({ operation: 'featureTools' })
        if (!Array.isArray(result.items) || !result.items.every(isTool)) return
        if (live && request === version) { setTools(result.items); setMessage('') }
      } catch { if (live && request === version) setMessage('工具列表暂时不可用') }
    }
    const onChange = () => { void refresh() }
    const listener = bridge.addFeatureToolsListener?.(onChange).catch(() => null)
    document.addEventListener('visibilitychange', onChange)
    void refresh()
    return () => { live = false; document.removeEventListener('visibilitychange', onChange); void listener?.then(handle => handle?.remove()).catch(() => {}) }
  }, [bridge])
  const open = async (tool?: Tool) => {
    try {
      setMessage('')
      await bridge.companionDashboard(tool ? { operation: 'featureOpen', id: tool.id, revision: tool.revision } : { operation: 'featureLibrary' })
    } catch { setMessage('工具未能打开，请重试。') }
  }
  return <section className="authored-tools" aria-label="我的工具">
    <div className="authored-tools-heading"><h3><Wrench size={17} aria-hidden="true" />我的工具</h3>
      <button type="button" onClick={() => { void open() }}>功能工坊 <ChevronRight size={15} aria-hidden="true" /></button></div>
    {tools.length > 0 ? <div className="authored-tool-links">{(expanded ? tools : tools.slice(0, 4)).map(tool =>
      <button type="button" key={tool.id} onClick={() => { void open(tool) }}>{tool.title}</button>)}
      {tools.length > 4 && <button type="button" className="authored-tool-more" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
        {expanded ? '收起' : `显示其余 ${tools.length - 4} 项`}</button>}</div> :
      <p>在功能工坊启用工具后，可从这里直接打开。</p>}
    {create && <button type="button" className="authored-tool-create" onClick={() => {
      void bridge.insertTaskDraft('请为鲸鱼助手创建一个新功能。我希望它能：').catch(() => setMessage('无法进入创作对话'))
    }}>去对话创作</button>}
    {message && <small aria-live="polite">{message}</small>}
  </section>
}
