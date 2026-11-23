export interface ChecklistWidgetSpec {
  type: 'checklist'
  title: string
  items: Array<{ text: string; checked?: boolean; tag?: string }>
}

export interface CompareWidgetSpec {
  type: 'compare'
  title: string
  columns: string[]
  rows: Array<{ name: string; values: string[]; highlight?: boolean; badge?: string }>
}

export interface TimerWidgetSpec {
  type: 'timer'
  title: string
  durationSec: number
  label?: string
}

export interface CalcWidgetSpec {
  type: 'calc'
  title: string
  fields: Array<{ key: string; label: string; min: number; max: number; step?: number; value: number; unit?: string }>
  formula: string
  resultLabel: string
  resultUnit?: string
}

export type WhaleWidgetSpec = ChecklistWidgetSpec | CompareWidgetSpec | TimerWidgetSpec | CalcWidgetSpec

export interface ExtractedWidgets {
  cleanMarkdown: string
  widgets: WhaleWidgetSpec[]
  hasPendingWidget: boolean
}

function isValidWidget(obj: unknown): obj is WhaleWidgetSpec {
  if (!obj || typeof obj !== 'object') return false
  const rec = obj as Record<string, unknown>
  if (typeof rec.title !== 'string' || !rec.title.trim()) return false
  if (rec.type === 'checklist') {
    return Array.isArray(rec.items) && rec.items.length > 0 && rec.items.every(i => i && typeof (i as Record<string, unknown>).text === 'string')
  }
  if (rec.type === 'compare') {
    return Array.isArray(rec.columns) && rec.columns.length > 0 && Array.isArray(rec.rows) && rec.rows.length > 0
  }
  if (rec.type === 'timer') {
    return typeof rec.durationSec === 'number' && Number.isFinite(rec.durationSec) && rec.durationSec > 0 && rec.durationSec <= 86400
  }
  if (rec.type === 'calc') {
    return Array.isArray(rec.fields) && rec.fields.length > 0 && typeof rec.formula === 'string' && typeof rec.resultLabel === 'string'
  }
  return false
}

/**
 * 从 Assistant Markdown 正文中安全提取 ```whale-widget ... ``` 声明式交互卡片块。
 * 格式非法时自动回退保留原代码块，绝不白屏或崩溃。
 */
export function extractWhaleWidgets(markdown: string, isStreaming = false): ExtractedWidgets {
  if (!markdown || !markdown.includes('```whale-widget')) {
    return { cleanMarkdown: markdown, widgets: [], hasPendingWidget: false }
  }
  const widgets: WhaleWidgetSpec[] = []
  let clean = markdown.replace(/```whale-widget\s*\n([\s\S]*?)```/g, (full, body: string) => {
    try {
      const parsed: unknown = JSON.parse(body.trim())
      if (isValidWidget(parsed)) {
        widgets.push(parsed)
        return ''
      }
    } catch {
      // Malformed JSON falls back to regular code block.
    }
    return full
  })
  let hasPendingWidget = false
  if (isStreaming && /```whale-widget\b/.test(clean)) {
    hasPendingWidget = true
    clean = clean.replace(/```whale-widget[\s\S]*$/, '').trimEnd()
  }
  return { cleanMarkdown: clean.trim(), widgets, hasPendingWidget }
}
