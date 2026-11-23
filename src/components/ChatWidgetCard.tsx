import { useEffect, useMemo, useState } from 'react'
import { Check, CheckSquare, Hammer, Pause, Play, RotateCcw, Sliders, Sparkles, Square, Table2, Timer } from 'lucide-react'

import type {
  CalcWidgetSpec,
  ChecklistWidgetSpec,
  CompareWidgetSpec,
  TimerWidgetSpec,
  WhaleWidgetSpec,
} from './chatWidgetProtocol'

/** 安全的纯四则/幂运算递归下降求值器（零 eval / 零 Function）。 */
function safeEvalFormula(formula: string, vars: Record<string, number>): number | null {
  const tokens: string[] = []
  const re = /\s*([A-Za-z_][A-Za-z0-9_]*|\d+(?:\.\d+)?|\*\*|[+\-*/()])/g
  let lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(formula)) !== null) {
    if (m.index !== lastIndex && formula.slice(lastIndex, m.index).trim() !== '') return null
    tokens.push(m[1])
    lastIndex = re.lastIndex
  }
  if (formula.slice(lastIndex).trim() !== '' || tokens.length === 0) return null
  let pos = 0
  const peek = () => tokens[pos]
  const consume = () => tokens[pos++]

  function parseExpr(): number {
    let left = parseTerm()
    while (peek() === '+' || peek() === '-') {
      const op = consume()
      const right = parseTerm()
      left = op === '+' ? left + right : left - right
    }
    return left
  }
  function parseTerm(): number {
    let left = parsePower()
    while (peek() === '*' || peek() === '/') {
      const op = consume()
      const right = parsePower()
      left = op === '*' ? left * right : (right === 0 ? NaN : left / right)
    }
    return left
  }
  function parsePower(): number {
    let base = parseUnary()
    if (peek() === '**') {
      consume()
      base = Math.pow(base, parsePower())
    }
    return base
  }
  function parseUnary(): number {
    if (peek() === '-') { consume(); return -parseUnary() }
    if (peek() === '+') { consume(); return parseUnary() }
    return parsePrimary()
  }
  function parsePrimary(): number {
    const tok = peek()
    if (!tok) return NaN
    if (tok === '(') {
      consume()
      const v = parseExpr()
      if (peek() === ')') consume()
      return v
    }
    if (/^\d/.test(tok)) {
      consume()
      return Number(tok)
    }
    if (/^[A-Za-z_]/.test(tok)) {
      consume()
      return typeof vars[tok] === 'number' ? vars[tok] : NaN
    }
    return NaN
  }

  const out = parseExpr()
  return pos === tokens.length && Number.isFinite(out) ? out : null
}

function formatTimer(sec: number): string {
  const s = Math.max(0, Math.floor(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  if (h > 0) return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}

export interface ChatWidgetCardProps {
  spec: WhaleWidgetSpec
  storageKey: string
  onSaveToWorkshop?: (prompt: string) => void
  onSendAction?: (prompt: string) => void
}

export function ChatWidgetCard({ spec, storageKey, onSaveToWorkshop, onSendAction }: ChatWidgetCardProps) {
  const saveWorkshopPrompt = () => {
    const jsonPreview = JSON.stringify(spec).slice(0, 600)
    onSaveToWorkshop?.(`请把刚才聊天里的交互卡片「${spec.title}」升级制作成一个离线功能工坊小工具并立即启用。原始结构参考：${jsonPreview}`)
  }

  return (
    <div className="whale-a2ui-card" data-widget-type={spec.type}>
      <div className="whale-a2ui-head">
        <span className="whale-a2ui-title">
          {spec.type === 'checklist' && <CheckSquare size={15} aria-hidden="true" />}
          {spec.type === 'compare' && <Table2 size={15} aria-hidden="true" />}
          {spec.type === 'timer' && <Timer size={15} aria-hidden="true" />}
          {spec.type === 'calc' && <Sliders size={15} aria-hidden="true" />}
          <strong>{spec.title}</strong>
        </span>
        {onSaveToWorkshop && (
          <button type="button" className="whale-a2ui-pin-btn" onClick={saveWorkshopPrompt} title="固化为离线小工具">
            <Hammer size={12} aria-hidden="true" /> 收入工坊
          </button>
        )}
      </div>

      {spec.type === 'checklist' && (
        <ChecklistBody spec={spec} storageKey={storageKey} onSendAction={onSendAction} />
      )}
      {spec.type === 'compare' && <CompareBody spec={spec} />}
      {spec.type === 'timer' && <TimerBody spec={spec} />}
      {spec.type === 'calc' && <CalcBody spec={spec} />}
    </div>
  )
}

function ChecklistBody({ spec, storageKey, onSendAction }: { spec: ChecklistWidgetSpec; storageKey: string; onSendAction?: (prompt: string) => void }) {
  const [checked, setChecked] = useState<boolean[]>(() => {
    try {
      const saved = window.localStorage.getItem(`whale.a2ui.${storageKey}`)
      if (saved) {
        const arr: unknown = JSON.parse(saved)
        if (Array.isArray(arr) && arr.length === spec.items.length) return arr.map(Boolean)
      }
    } catch { /* ignore */ }
    return spec.items.map(i => Boolean(i.checked))
  })

  const toggle = (idx: number) => {
    setChecked(prev => {
      const next = prev.map((v, i) => (i === idx ? !v : v))
      try { window.localStorage.setItem(`whale.a2ui.${storageKey}`, JSON.stringify(next)) } catch { /* ignore */ }
      return next
    })
  }

  const doneCount = checked.filter(Boolean).length
  const pct = Math.round((doneCount / Math.max(1, spec.items.length)) * 100)
  const remaining = spec.items.filter((_, idx) => !checked[idx]).map(i => i.text)

  return (
    <div className="whale-a2ui-body">
      <div className="whale-a2ui-progress-row">
        <div className="whale-a2ui-progress-track">
          <div className="whale-a2ui-progress-fill" style={{ width: `${pct}%` }} />
        </div>
        <span className="whale-a2ui-progress-label">{doneCount}/{spec.items.length}</span>
      </div>
      <div className="whale-a2ui-checklist">
        {spec.items.map((item, idx) => (
          <button
            key={`${idx}-${item.text.slice(0, 12)}`}
            type="button"
            className={`whale-a2ui-check-row${checked[idx] ? ' is-done' : ''}`}
            onClick={() => toggle(idx)}
          >
            {checked[idx] ? <Check size={15} className="whale-a2ui-check-ico is-done" /> : <Square size={15} className="whale-a2ui-check-ico" />}
            <span className="whale-a2ui-check-text">{item.text}</span>
            {item.tag && <span className="whale-a2ui-tag">{item.tag}</span>}
          </button>
        ))}
      </div>
      {onSendAction && remaining.length > 0 && (
        <div className="whale-a2ui-foot">
          <button
            type="button"
            className="whale-a2ui-action-btn"
            onClick={() => onSendAction(`请帮我把「${spec.title}」里未完成的 ${remaining.length} 项加入秘书待办：${remaining.slice(0, 6).join('；')}`)}
          >
            <Sparkles size={13} aria-hidden="true" /> 同步未完成项到待办
          </button>
        </div>
      )}
    </div>
  )
}

function CompareBody({ spec }: { spec: CompareWidgetSpec }) {
  const [onlyHighlight, setOnlyHighlight] = useState(false)
  const hasHighlights = spec.rows.some(r => r.highlight)
  const visibleRows = onlyHighlight ? spec.rows.filter(r => r.highlight) : spec.rows
  return (
    <div className="whale-a2ui-body">
      {hasHighlights && spec.rows.length > 2 && (
        <div className="whale-a2ui-compare-bar">
          <button type="button" className={`whale-a2ui-mini-pill${!onlyHighlight ? ' is-active' : ''}`} onClick={() => setOnlyHighlight(false)}>
            全部方案 ({spec.rows.length})
          </button>
          <button type="button" className={`whale-a2ui-mini-pill${onlyHighlight ? ' is-active' : ''}`} onClick={() => setOnlyHighlight(true)}>
            仅看推荐
          </button>
        </div>
      )}
      <div className="whale-table-wrap">
        <table className="whale-a2ui-table">
          <thead>
            <tr>
              <th>选项</th>
              {spec.columns.map(col => <th key={col}>{col}</th>)}
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((row, rIdx) => (
              <tr key={`${rIdx}-${row.name}`} className={row.highlight ? 'is-highlight' : undefined}>
                <td>
                  <strong>{row.name}</strong>
                  {row.badge && <span className="whale-a2ui-tag">{row.badge}</span>}
                </td>
                {spec.columns.map((_, cIdx) => <td key={cIdx}>{row.values[cIdx] ?? '—'}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function TimerBody({ spec }: { spec: TimerWidgetSpec }) {
  const total = Math.max(1, Math.round(spec.durationSec))
  const [remaining, setRemaining] = useState(total)
  const [running, setRunning] = useState(false)

  useEffect(() => {
    if (!running) return
    if (remaining <= 0) {
      setRunning(false)
      return
    }
    const id = window.setInterval(() => {
      setRemaining(prev => {
        if (prev <= 1) {
          setRunning(false)
          return 0
        }
        return prev - 1
      })
    }, 1000)
    return () => window.clearInterval(id)
  }, [running, remaining])

  const pct = Math.round(((total - remaining) / total) * 100)
  return (
    <div className="whale-a2ui-body whale-a2ui-timer">
      <div className="whale-a2ui-timer-readout">
        <span className="whale-a2ui-timer-clock">{formatTimer(remaining)}</span>
        <span className="whale-a2ui-timer-sub">{remaining === 0 ? '计时已结束' : (spec.label || (running ? '计时中…' : '点击开始计时'))}</span>
      </div>
      <div className="whale-a2ui-progress-track">
        <div className="whale-a2ui-progress-fill" style={{ width: `${pct}%` }} />
      </div>
      <div className="whale-a2ui-timer-actions">
        <button
          type="button"
          className="whale-a2ui-action-btn is-primary"
          onClick={() => {
            if (remaining === 0) setRemaining(total)
            setRunning(v => !v)
          }}
        >
          {running ? <><Pause size={14} /> 暂停</> : <><Play size={14} /> {remaining === 0 ? '重新开始' : '开始'}</>}
        </button>
        <button
          type="button"
          className="whale-a2ui-action-btn"
          onClick={() => { setRunning(false); setRemaining(total) }}
        >
          <RotateCcw size={14} /> 重置
        </button>
      </div>
    </div>
  )
}

function CalcBody({ spec }: { spec: CalcWidgetSpec }) {
  const [values, setValues] = useState<Record<string, number>>(() => {
    const init: Record<string, number> = {}
    spec.fields.forEach(f => { init[f.key] = Number.isFinite(f.value) ? f.value : f.min })
    return init
  })

  const result = useMemo(() => safeEvalFormula(spec.formula, values), [spec.formula, values])

  return (
    <div className="whale-a2ui-body whale-a2ui-calc">
      <div className="whale-a2ui-calc-result">
        <span className="whale-a2ui-calc-label">{spec.resultLabel}</span>
        <strong className="whale-a2ui-calc-num">
          {result === null ? '—' : Number.isInteger(result) ? result.toLocaleString() : result.toLocaleString(undefined, { maximumFractionDigits: 2 })}
          {spec.resultUnit ? <small> {spec.resultUnit}</small> : null}
        </strong>
      </div>
      <div className="whale-a2ui-calc-fields">
        {spec.fields.map(f => {
          const cur = values[f.key] ?? f.min
          return (
            <label key={f.key} className="whale-a2ui-slider-row">
              <span className="whale-a2ui-slider-head">
                <span>{f.label}</span>
                <strong>{cur}{f.unit ?? ''}</strong>
              </span>
              <input
                type="range"
                min={f.min}
                max={f.max}
                step={f.step ?? 1}
                value={cur}
                onChange={e => setValues(prev => ({ ...prev, [f.key]: Number(e.target.value) }))}
              />
            </label>
          )
        })}
      </div>
    </div>
  )
}
