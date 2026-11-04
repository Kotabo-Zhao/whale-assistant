import { useEffect, useRef, useState } from 'react'
import { ArrowUpRight, ChevronDown, FileAudio, FileText, Image, Inbox } from 'lucide-react'
import { companionRows, type CompanionRow } from '../platform/companion'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'

type Row = CompanionRow
const string = (v: unknown) => typeof v === 'string' ? v : ''
const title = (row: Row) => string(row.displayName) || (row.source === 'original' ? '已存原件' : row.text.split('\n', 1)[0].slice(0, 80))
const icon = (row: Row) => string(row.mime).startsWith('audio/') || row.mediaKind === 'audio_transcript' ? FileAudio
  : string(row.mime).startsWith('image/') || row.mediaKind === 'image_ocr' ? Image : FileText
const stateLabel = (row: Row) => row.parseState === 'needs_retry' ? '待重试' : row.parseState === 'queued' ? '待解析'
  : row.parseState === 'processing' ? '解析中' : row.coverage === 'partial' ? '部分已提取' : row.coverage === 'model_description' ? '图片描述'
    : row.coverage === 'extracted_text' ? '可读正文' : row.source === 'original' ? '已存原件' : '已保存'
const timeLabel = (value: unknown) => typeof value === 'number' ? new Date(value).toLocaleString(undefined, { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''

function useCaptureUpdates(active = true) {
  const [change, bump] = useState(0)
  useEffect(() => {
    if (!active) return
    let live = true
    const refresh = () => { if (live && !document.hidden) bump(n => n + 1) }
    const subscription = runtimeBridge.addKnowledgeCapturesListener?.(refresh).catch(() => undefined)
    if (subscription) void subscription.then(refresh)
    else refresh()
    document.addEventListener('visibilitychange', refresh)
    return () => { live = false; document.removeEventListener('visibilitychange', refresh); void subscription?.then(handle => handle?.remove()) }
  }, [active])
  return change
}

export function CaptureShelf({ revision, active = true, open }: { revision: number; active?: boolean; open: (row: Row) => void }) {
  const change = useCaptureUpdates(active)
  const [items, setItems] = useState<Row[]>([])
  const [expanded, setExpanded] = useState(false)
  const [error, setError] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (!change || !active || document.hidden) return
    let live = true
    void runtimeBridge.companionDashboard({ operation: 'knowledgeRecentCaptures' }).then(result => {
      if (live) { setItems(companionRows(result.items)); setError(false) }
    }).catch(() => { if (live) setError(true) })
    return () => { live = false }
  }, [revision, change, retry, active])
  return <section className="capture-shelf" aria-label={t("最近投放")}>
    <div className="capture-heading"><h4><Inbox size={18} />{t("最近投放")}</h4>
      {items.length > 2 && <button type="button" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : '查看全部 ' + items.length}<ChevronDown size={15} /></button>}</div>
    {error ? <button type="button" onClick={() => setRetry(v => v + 1)}>未能读取最近投放，重试</button>
      : items.length === 0 ? <p>{t("从其他 App 收藏后，可以在这里回看。")}</p> : <div className="capture-grid">{items.slice(0, expanded ? 8 : 2).map(row => {
        const Kind = icon(row)
        return <button type="button" className="capture-card" key={row.id} onClick={() => open(row)}>
          <Kind size={20} /><span><strong>{title(row)}</strong><small>{[string(row.shareOrigin), timeLabel(row.capturedAt), stateLabel(row)].filter(Boolean).join(' · ')}</small></span><ArrowUpRight size={15} /></button>
      })}</div>}
  </section>
}

function Transcript({ value }: { value: string }) {
  return <div className="capture-transcript">{value.split('\n').map((line, index) => {
    const match = /^\[(\d+(?::\d{2}){1,2})\]\s*(.*)$/.exec(line)
    return match ? <p className="capture-cue" key={index}><span className="capture-time">{match[1]}</span><span>{match[2]}</span></p>
      : <p className="knowledge-detail-content" key={index}>{line}</p>
  })}</div>
}

export function CaptureReader({ reference, onClose, manage }: { reference: Row; onClose: () => void; manage: (row: Row) => void }) {
  const change = useCaptureUpdates()
  const [pages, setPages] = useState([0])
  const offset = pages[pages.length - 1]
  const [result, setResult] = useState<{ row: Row; parts: Row[]; next: number | null; offset: number } | null>(null)
  const [problem, setProblem] = useState('')
  const [reload, setReload] = useState(0)
  const closeButton = useRef<HTMLButtonElement>(null)
  const scroll = useRef<HTMLElement>(null)
  useEffect(() => {
    const previous = document.activeElement
    closeButton.current?.focus()
    return () => { if (previous instanceof HTMLElement && previous.isConnected) previous.focus() }
  }, [])
  useEffect(() => { scroll.current?.scrollTo?.(0, 0) }, [offset])
  useEffect(() => {
    if (!change || document.hidden || typeof reference.createdAt !== 'number') return
    let live = true
    const generation = string(reference.generation) || string(reference.attachmentGeneration)
    void runtimeBridge.companionDashboard({ operation: 'knowledgeCaptureRead', id: reference.id, createdAt: reference.createdAt, generation, offset }).then(response => {
      if (!live) return
      if (response.missing) { setResult(null); setProblem('这份资料已删除或替换。'); return }
      const row = companionRows([response.row])[0]
      setResult({ row, parts: companionRows(response.items), next: typeof response.nextOffset === 'number' ? response.nextOffset : null, offset })
      setProblem('')
    }).catch(() => { if (live) { setResult(null); setProblem('读取未完成，可以重试。') } })
    return () => { live = false }
  }, [reference, change, offset, reload])
  const page = result?.offset === offset ? result : null
  const row = page?.row
  const transcript = string(row?.mime).startsWith('audio/') || page?.parts.some(p => p.mediaKind === 'audio_transcript')
  const heading = transcript ? '录音转写' : string(row?.mime).startsWith('image/') ? '图中内容' : '资料正文'
  return <div className="knowledge-detail-backdrop" onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); onClose() }
    if (event.key === 'Tab') {
      const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not([disabled])')
      const first = buttons[0]; const last = buttons[buttons.length - 1]
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
    } }}>
    <section ref={scroll} className="knowledge-detail-page capture-reader" role="dialog" aria-modal="true" aria-labelledby="capture-reader-title">
      <header><div><span className="knowledge-type">收藏阅读</span><h4 id="capture-reader-title">{row ? title(row) : '读取收藏'}</h4>
        {row && <small>{[string(row.shareOrigin), stateLabel(row)].filter(Boolean).join(' · ')}</small>}</div>
        <button type="button" className="button button-secondary" ref={closeButton} onClick={onClose}>关闭</button></header>
      {problem ? <p role="status">{problem} <button type="button" onClick={() => setReload(v => v + 1)}>重新读取</button></p>
        : !row || !page ? <p role="status">正在读取资料…</p> : <>
          <h5>{heading}</h5>
          {page.parts.length ? page.parts.map(part => <article key={part.id} className="capture-part">
            {typeof part.chunkCount === 'number' && part.chunkCount > 1 && <small>第 {Number(part.chunkIndex) + 1} / {part.chunkCount} 段</small>}
            {part.mediaKind === 'audio_transcript' ? <Transcript value={part.text} /> : <p className="knowledge-detail-content">{part.text}</p>}</article>)
            : row.source === 'original' ? <p>{row.audioFromSeconds === 600 || row.canContinueAudio === true ? '前十分钟的已有转写仍可检索，后续进度可在原件详情查看。'
              : row.parseState === 'needs_retry' ? '本次未提取到正文，可在资料详情中重新解析。'
              : row.parseState === 'queued' || row.parseState === 'processing' ? '原件已保存。后台解析完成后，正文会显示在这里。'
                : '这份原件还没有可阅读的文字。'}</p> : <p className="knowledge-detail-content">{row.text}</p>}
          {(pages.length > 1 || page.next !== null) && <nav aria-label="正文分页" className="workspace-card-actions">
            {pages.length > 1 && <button type="button" onClick={() => setPages(p => p.slice(0, -1))}>上一页正文</button>}
            {page.next !== null && <button type="button" onClick={() => setPages(p => [...p, page.next!])}>下一页正文</button>}
          </nav>}
          <div className="workspace-card-actions"><button type="button" onClick={() => manage(row)}>打开资料详情</button></div>
        </>}
    </section></div>
}
