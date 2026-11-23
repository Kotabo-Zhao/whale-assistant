import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import MarkdownIt from 'markdown-it'
import texmath from 'markdown-it-texmath'
import katex from 'katex'
import 'katex/dist/katex.min.css'
import {
  ArrowDown,
  ArrowUp,
  ExternalLink,
  FileText,
  FolderOpen,
  Share2,
  BellRing,
  BookmarkCheck,
  BookOpen,
  Brain,
  Check,
  CheckCircle2,
  Copy,
  Hammer,
  ImagePlus,
  ListTodo,
  MessageSquarePlus,
  MessagesSquare,
  Mic,
  Pencil,
  RotateCcw,
  Slash,
  Square,
  Trash2,
  Wrench,
  X,
} from 'lucide-react'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanAvatar from '../assets/whale-chan-avatar.png'
import usagiAvatar from '../assets/usagi-avatar.png'
import { CuteIcon, type CuteIconName } from '../components/CuteIcon'
import { AgentStatusIcon } from '../components/AgentStatusIcon'
import { ChatMediaCard } from '../components/ChatMediaCard'
import { ChatWidgetCard } from '../components/ChatWidgetCard'
import { extractWhaleWidgets } from '../components/chatWidgetProtocol'
import { AiWorkspaceModal } from '../components/AiWorkspaceModal'
import { t } from '../i18n'
import { useInterfaceStyle } from '../theme'
import type { RuntimeBridge, WhaleNativeChatStep } from '../platform/types'
import type { AppView } from '../ui/appNavigation'
import './WhaleChatScreen.css'

export interface WhaleChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  steps?: WhaleNativeChatStep[]
  model?: string
  elapsedMs?: number
  compressionRatio?: number
  savedChars?: number
  cacheHitRatio?: number
  reasoning?: string
  reasoningMs?: number
  error?: boolean
  suggestions?: string[]
  steered?: boolean
  images?: string[]
}

export interface WhaleChatSession {
  id: string
  title: string
  updatedAt: number
  messages: WhaleChatMessage[]
}

interface ActionReceipt {
  key: string
  kind: 'secretary' | 'knowledge' | 'memory' | 'workshop' | 'file'
  title: string
  view: AppView
  fileName?: string
  actionLabel: string
  followUpPrompt?: string
  followUpLabel?: string
}

const STARTER_PROMPTS: ReadonlyArray<{ label: string; text: string; icon: CuteIconName }> = [
  { label: '今日待办', text: '帮我梳理一下今天最重要的三件事，按优先级排个顺序。', icon: 'tasks' },
  { label: '查记忆', text: '查一下我之前让你记住的偏好和重要信息。', icon: 'memory' },
  { label: '造小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。', icon: 'workshop' },
  { label: '设备状态', text: '帮我看看手机电量和当前有没有后台任务在运行。', icon: 'device' },
]

const QUICK_SLASH_ACTIONS = [
  { id: 'remind', label: '设提醒', prefix: '提醒我 ', sendNow: false },
  { id: 'save-doc', label: '存资料', prefix: '帮我把下面内容存入本地资料库并提炼要点：\n', sendNow: false },
  { id: 'recall', label: '查记忆', prefix: '查一下我之前让你记住的偏好和近期重要事项。', sendNow: true },
  { id: 'workshop', label: '造小工具', prefix: '帮我在功能工坊创作并立即启用一个离线小工具：', sendNow: false },
] as const

function buildTimeAwareStarters(): ReadonlyArray<{ label: string; text: string; icon: CuteIconName }> {
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 11) {
    return [
      { label: '早安规划', text: '帮我看看今天的日程和未完成待办，按优先级排个上午行动清单。', icon: 'tasks' },
      { label: '查记忆', text: '查一下我之前让你记住的偏好和重要信息。', icon: 'memory' },
      { label: '造小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。', icon: 'workshop' },
      { label: '设备状态', text: '帮我看看手机电量和当前有没有后台任务在运行。', icon: 'device' },
    ]
  }
  if (hour >= 19 || hour < 5) {
    return [
      { label: '晚间复盘', text: '帮我复盘一下今天还没完成的待办，顺手把需要明天做的重新排一下。', icon: 'tasks' },
      { label: '查记忆', text: '查一下我之前让你记住的偏好和重要信息。', icon: 'memory' },
      { label: '明日提醒', text: '帮我给明天早上定个闹钟或提醒，先问我几点起。', icon: 'notifications' },
      { label: '造小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。', icon: 'workshop' },
    ]
  }
  return STARTER_PROMPTS
}

function stripStreamingMarker(text: string): string {
  const idx = text.indexOf('<!--whale_next')
  return idx >= 0 ? text.slice(0, idx).trimEnd() : text
}

async function compressImageToDataUrl(file: File, maxEdge = 1024): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.onload = () => {
      const rawUrl = typeof reader.result === 'string' ? reader.result : ''
      if (!rawUrl) { reject(new Error('图片内容为空')); return }
      const img = new window.Image()
      img.onerror = () => resolve(rawUrl)
      img.onload = () => {
        const scale = Math.min(1, maxEdge / Math.max(img.width || 1, img.height || 1))
        const w = Math.max(1, Math.round(img.width * scale))
        const h = Math.max(1, Math.round(img.height * scale))
        const canvas = document.createElement('canvas')
        canvas.width = w
        canvas.height = h
        const ctx = canvas.getContext('2d')
        if (!ctx) { resolve(rawUrl); return }
        ctx.drawImage(img, 0, 0, w, h)
        resolve(canvas.toDataURL('image/jpeg', 0.78))
      }
      img.src = rawUrl
    }
    reader.readAsDataURL(file)
  })
}

const LEGACY_STORAGE_KEY = 'whale.nativeChatHistory.v1'
const SESSIONS_STORAGE_KEY = 'whale.nativeChatSessions.v2'
const ACTIVE_SESSION_KEY = 'whale.nativeChatActiveSession.v2'
const MAX_SESSIONS = 24
const MAX_MESSAGES_PER_SESSION = 40

function extractActionReceipts(steps?: WhaleNativeChatStep[]): ActionReceipt[] {
  if (!steps || steps.length === 0) return []
  const receipts: ActionReceipt[] = []
  const seen = new Set<string>()
  for (const step of steps) {
    if (!step.ok) continue
    if (step.tool === 'whale_secretary_add' && !seen.has('sec-add')) {
      seen.add('sec-add')
      receipts.push({
        key: 'sec-add',
        kind: 'secretary',
        title: step.label || '已记录新的待办事项',
        view: 'assistant-tasks',
        actionLabel: '查看事项',
        followUpLabel: '查看全部待办',
        followUpPrompt: '列出我当前所有未完成的事项。',
      })
    } else if (step.tool === 'whale_secretary_complete' && !seen.has('sec-done')) {
      seen.add('sec-done')
      receipts.push({
        key: 'sec-done',
        kind: 'secretary',
        title: '事项已标记完成',
        view: 'assistant-tasks',
        actionLabel: '查看事项',
      })
    } else if (step.tool === 'whale_knowledge_save' && !seen.has('know-save')) {
      seen.add('know-save')
      receipts.push({
        key: 'know-save',
        kind: 'knowledge',
        title: '已自动存入本地资料库',
        view: 'assistant-memory',
        actionLabel: '打开资料库',
      })
    } else if (step.tool === 'whale_owner_profile_edit' && !seen.has('profile-edit')) {
      seen.add('profile-edit')
      receipts.push({
        key: 'profile-edit',
        kind: 'memory',
        title: step.label || '主人档案已更新',
        view: 'assistant-memory',
        actionLabel: '查看记忆',
      })
    } else if ((step.tool === 'whale_goal_create' || step.tool === 'whale_goal_update') && !seen.has('goal')) {
      seen.add('goal')
      receipts.push({
        key: 'goal',
        kind: 'secretary',
        title: step.label || '已更新持续目标图',
        view: 'assistant-tasks',
        actionLabel: '查看事项',
      })
    } else if (step.tool === 'whale_playbook_save' && !seen.has('playbook')) {
      seen.add('playbook')
      receipts.push({
        key: 'playbook',
        kind: 'memory',
        title: step.label || '已沉淀个人习惯规则',
        view: 'assistant-memory',
        actionLabel: '查看记忆',
      })
    } else if (step.tool === 'whale_memory_save' && !seen.has('mem-save')) {
      seen.add('mem-save')
      receipts.push({
        key: 'mem-save',
        kind: 'memory',
        title: '已记入长期记忆',
        view: 'assistant-memory',
        actionLabel: '查看记忆',
      })
    } else if ((step.tool === 'whale_workshop_draft' || step.tool === 'whale_workshop_activate') && !seen.has('ws')) {
      seen.add('ws')
      receipts.push({
        key: 'ws',
        kind: 'workshop',
        title: '离线小工具已就绪',
        view: 'tools',
        actionLabel: '打开工坊',
      })
    } else if (step.tool === 'whale_file_export' || step.tool === 'whale_html_to_pdf') {
      const extracted = (step.label || '').split(/[：:]/).slice(1).join(':').trim()
      const fileName = extracted || (step.tool === 'whale_html_to_pdf' ? 'document.pdf' : '')
      const fkey = `file-${fileName || receipts.length}`
      if (fileName && !seen.has(fkey)) {
        seen.add(fkey)
        receipts.push({
          key: fkey,
          kind: 'file',
          title: fileName,
          view: 'whale-chat',
          actionLabel: '打开文件',
          fileName,
        })
      }
    }
  }
  return receipts
}

function looksLikeArchivableContent(text: string): boolean {
  const trimmed = text.trim()
  if (trimmed.length >= 160) return true
  return /https?:\/\/\S+/i.test(trimmed)
}

function createSession(title = '新对话', messages: WhaleChatMessage[] = []): WhaleChatSession {
  return {
    id: `s-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    title,
    updatedAt: Date.now(),
    messages,
  }
}

function loadSessions(): WhaleChatSession[] {
  try {
    const raw = window.localStorage.getItem(SESSIONS_STORAGE_KEY)
    if (raw) {
      const parsed: unknown = JSON.parse(raw)
      if (Array.isArray(parsed) && parsed.length > 0) {
        return (parsed as WhaleChatSession[]).slice(0, MAX_SESSIONS)
      }
    }
    const legacy = window.localStorage.getItem(LEGACY_STORAGE_KEY)
    if (legacy) {
      const parsed: unknown = JSON.parse(legacy)
      if (Array.isArray(parsed) && parsed.length > 0) {
        const msgs = (parsed as WhaleChatMessage[]).slice(-MAX_MESSAGES_PER_SESSION)
        const firstUser = msgs.find(m => m.role === 'user')?.text.trim()
        return [createSession(firstUser ? firstUser.slice(0, 18) : '历史对话', msgs)]
      }
    }
  } catch {
    // Ignore corrupted storage.
  }
  return [createSession()]
}

function loadActiveSessionId(sessions: WhaleChatSession[]): string {
  try {
    const saved = window.localStorage.getItem(ACTIVE_SESSION_KEY)
    if (saved && sessions.some(s => s.id === saved)) return saved
  } catch {
    // Ignore storage errors.
  }
  return sessions[0].id
}

export interface WhaleChatScreenProps {
  bridge: RuntimeBridge
  onNavigate: (view: AppView) => void
  isVisible?: boolean
}

export function WhaleChatScreen({ bridge, onNavigate, isVisible = true }: WhaleChatScreenProps) {
  const style = useInterfaceStyle()
  const isUsagi = style === 'usagi'
  const activeAvatar = isUsagi ? usagiAvatar : whaleChanAvatar
  const heroMark = style === 'simple' ? grokMark : activeAvatar
  const [sessions, setSessions] = useState<WhaleChatSession[]>(loadSessions)
  const [activeId, setActiveId] = useState<string>(() => loadActiveSessionId(loadSessions()))
  const [sessionsOpen, setSessionsOpen] = useState(false)
  const [workspaceOpen, setWorkspaceOpen] = useState(false)
  const [slashOpen, setSlashOpen] = useState(false)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [input, setInput] = useState('')
  const [pendingImages, setPendingImages] = useState<string[]>([])
  const [pendingFiles, setPendingFiles] = useState<Array<{ fileName: string; absolutePath: string; bytes: number }>>([])
  const [sending, setSending] = useState(false)
  const [streamingText, setStreamingText] = useState('')
  const [streamingReasoning, setStreamingReasoning] = useState('')
  const streamingReasoningRef = useRef('')
  const reasoningStartRef = useRef<number>(0)
  const [streamingSteps, setStreamingSteps] = useState<WhaleNativeChatStep[]>([])
  const [streamingPhase, setStreamingPhase] = useState('')
  const [showScrollBottom, setShowScrollBottom] = useState(false)
  const [activeWorkers, setActiveWorkers] = useState<Array<{ id: string; title: string; status: string; attention?: string }>>([])
  const [waitElapsedSec, setWaitElapsedSec] = useState(0)
  const petSyncCursorRef = useRef<number>((() => {
    try {
      const saved = Number(window.localStorage.getItem('whale.petSyncCursor.v1') || '0')
      return Number.isFinite(saved) && saved > 0 ? saved : 0
    } catch { return 0 }
  })())
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [recording, setRecording] = useState(false)
  const [transcribing, setTranscribing] = useState(false)
  const [voiceNote, setVoiceNote] = useState('')
  const activeRequestRef = useRef<string | null>(null)
  const pendingFollowUpRef = useRef<string[]>([])
  const listRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const md = useMemo(() => {
    const inst = new MarkdownIt({ html: false, linkify: true, breaks: true })
    inst.use(texmath, {
      engine: katex,
      delimiters: ['dollars', 'brackets'],
      katexOptions: { throwOnError: false, strict: false },
    })
    inst.renderer.rules.fence = (tokens, idx) => {
      const token = tokens[idx]
      const lang = (token.info || '').trim().split(/\s+/)[0] || 'text'
      const rawCode = token.content || ''
      const escaped = inst.utils.escapeHtml(rawCode)
      const encoded = encodeURIComponent(rawCode)
      return `<div class="whale-code-block"><div class="whale-code-head"><span class="whale-code-lang">${inst.utils.escapeHtml(lang)}</span><button type="button" class="whale-code-copy-btn" data-code="${encoded}">复制代码</button></div><pre><code>${escaped}</code></pre></div>`
    }
    inst.renderer.rules.table_open = () => '<div class="whale-table-wrap"><table>'
    inst.renderer.rules.table_close = () => '</table></div>'
    return inst
  }, [])
  const renderedHtmlCache = useRef<Map<string, string>>(new Map())
  const renderCachedMarkdown = (id: string, text: string): string => {
    const key = `${id}:${text.length}`
    const hit = renderedHtmlCache.current.get(key)
    if (hit !== undefined) return hit
    const html = md.render(text)
    if (renderedHtmlCache.current.size > 120) renderedHtmlCache.current.clear()
    renderedHtmlCache.current.set(key, html)
    return html
  }

  const currentSession = useMemo(
    () => sessions.find(s => s.id === activeId) ?? sessions[0],
    [sessions, activeId],
  )
  const messages = currentSession.messages
  const showArchiveHint = !sending && looksLikeArchivableContent(input) && !input.startsWith('帮我把下面内容存入')

  useEffect(() => {
    try {
      // 图片 Base64 仅保留在当前内存会话中，避免撑爆 WebView 5MB localStorage 配额导致后续会话丢失
      const compactSessions = sessions.slice(0, MAX_SESSIONS).map(s => ({
        ...s,
        messages: s.messages.map(m => (m.images ? { ...m, images: undefined } : m)),
      }))
      window.localStorage.setItem(SESSIONS_STORAGE_KEY, JSON.stringify(compactSessions))
      window.localStorage.setItem(ACTIVE_SESSION_KEY, currentSession.id)
    } catch {
      // Ignore storage quota errors.
    }
  }, [sessions, currentSession.id])

  const autoFollowRef = useRef(true)
  const programmaticScrollRef = useRef(false)
  useLayoutEffect(() => {
    const el = listRef.current
    if (!el || !autoFollowRef.current) return
    programmaticScrollRef.current = true
    el.scrollTop = el.scrollHeight
    const raf = window.requestAnimationFrame(() => {
      if (listRef.current && autoFollowRef.current) {
        listRef.current.scrollTop = listRef.current.scrollHeight
      }
      programmaticScrollRef.current = false
    })
    return () => {
      window.cancelAnimationFrame(raf)
      programmaticScrollRef.current = false
    }
  }, [messages, streamingText, streamingSteps, sending, isVisible])

  useEffect(() => {
    if (!bridge.addWhaleChatListener) return
    let disposed = false
    let cleanup: (() => Promise<void>) | undefined
    void bridge.addWhaleChatListener(event => {
      if (disposed || !activeRequestRef.current || event.requestId !== activeRequestRef.current) return
      if (event.type === 'delta' && event.text) {
        setStreamingText(prev => prev + event.text)
      } else if (event.type === 'reasoning' && event.text) {
        if (reasoningStartRef.current === 0) reasoningStartRef.current = Date.now()
        streamingReasoningRef.current += event.text
        setStreamingReasoning(streamingReasoningRef.current)
      } else if (event.type === 'step' && event.step) {
        setStreamingSteps(prev => [...prev, event.step!])
      } else if (event.type === 'phase' && event.phase) {
        if (event.phase === 'restarting') {
          setStreamingText('')
        }
        setStreamingPhase(event.phase)
      }
    }).then(handle => {
      if (disposed) {
        void handle.remove()
      } else {
        cleanup = () => handle.remove()
      }
    }).catch(() => {})
    return () => {
      disposed = true
      if (cleanup) void cleanup()
    }
  }, [bridge])

  const updateSessionMessages = (sessionId: string, updater: (prev: WhaleChatMessage[]) => WhaleChatMessage[], autoTitle?: string) => {
    setSessions(prev => prev.map(session => {
      if (session.id !== sessionId) return session
      const nextMsgs = updater(session.messages).slice(-MAX_MESSAGES_PER_SESSION)
      const nextTitle = autoTitle && (session.title === '新对话' || session.title.trim() === '')
        ? autoTitle.slice(0, 18)
        : session.title
      return { ...session, title: nextTitle, updatedAt: Date.now(), messages: nextMsgs }
    }))
  }

  const startNewSession = () => {
    if (sending) {
      void cancelCurrentTurn()
    }
    const now = Date.now()
    petSyncCursorRef.current = now
    try { window.localStorage.setItem('whale.petSyncCursor.v1', String(now)) } catch { /* ignore */ }
    const fresh = createSession()
    setSessions(prev => [fresh, ...prev].slice(0, MAX_SESSIONS))
    setActiveId(fresh.id)
    setSessionsOpen(false)
    setInput('')
    setPendingImages([])
  }

  const deleteSession = (id: string) => {
    if (sending) return
    setSessions(prev => {
      const remaining = prev.filter(s => s.id !== id)
      if (remaining.length === 0) {
        const fresh = createSession()
        setActiveId(fresh.id)
        return [fresh]
      }
      if (activeId === id) setActiveId(remaining[0].id)
      return remaining
    })
  }

  const commitRename = (id: string) => {
    const clean = renameDraft.trim()
    if (clean) {
      setSessions(prev => prev.map(s => (s.id === id ? { ...s, title: clean.slice(0, 28) } : s)))
    }
    setRenamingId(null)
    setRenameDraft('')
  }

  const sendTextRef = useRef<(raw: string, overrideImages?: string[]) => Promise<void>>(async () => {})
  useEffect(() => {
    const winWithPending = window as Window & { __whalePendingAsk?: { prompt: string; image?: string } }
    const dispatchAskDetail = (detail: string | { prompt?: string; image?: string } | undefined) => {
      if (typeof detail === 'string' && detail.trim()) {
        void sendTextRef.current(detail.trim())
      } else if (detail && typeof detail === 'object' && typeof detail.prompt === 'string' && detail.prompt.trim()) {
        const imgs = typeof detail.image === 'string' && detail.image.startsWith('data:image/') ? [detail.image] : undefined
        void sendTextRef.current(detail.prompt.trim(), imgs)
      }
    }
    const onMiniAppAsk = (ev: Event) => {
      if (winWithPending.__whalePendingAsk) {
        delete winWithPending.__whalePendingAsk
      }
      dispatchAskDetail((ev as CustomEvent<string | { prompt?: string; image?: string }>).detail)
    }
    window.addEventListener('whale-miniapp-ask', onMiniAppAsk)
    if (winWithPending.__whalePendingAsk) {
      const queued = winWithPending.__whalePendingAsk
      delete winWithPending.__whalePendingAsk
      window.setTimeout(() => dispatchAskDetail(queued), 20)
    }
    const pullPetTurns = async () => {
      try {
        const cursor = petSyncCursorRef.current
        const res = await bridge.companionDashboard?.({ operation: 'sharedChatRead', sinceMs: cursor })
        const rows = Array.isArray(res?.messages) ? (res.messages as Array<{ id?: string; role?: string; text?: string; source?: string; atMs?: number }>) : []
        const petRows = rows.filter(r => r.source === 'pet' && typeof r.id === 'string' && typeof r.text === 'string' && (r.role === 'user' || r.role === 'assistant') && Number(r.atMs ?? 0) > cursor)
        if (petRows.length === 0) return
        const maxAt = Math.max(...petRows.map(r => Number(r.atMs ?? 0)))
        if (maxAt > petSyncCursorRef.current) {
          petSyncCursorRef.current = maxAt
          try { window.localStorage.setItem('whale.petSyncCursor.v1', String(maxAt)) } catch { /* ignore */ }
        }
        setSessions(prev => {
          const targetId = prev.some(s => s.id === activeId) ? activeId : prev[0]?.id
          return prev.map(sess => {
            if (sess.id !== targetId) return sess
            const seenIds = new Set(sess.messages.map(m => m.id))
            const seenContent = new Set(sess.messages.map(m => `${m.role}:${m.text.trim()}`))
            const additions: WhaleChatMessage[] = petRows
              .filter(r => !seenIds.has(r.id!) && !seenContent.has(`${r.role}:${r.text!.trim()}`))
              .map(r => ({ id: r.id!, role: r.role as 'user' | 'assistant', text: r.text!, createdAt: Number(r.atMs ?? Date.now()) }))
            if (additions.length === 0) return sess
            return { ...sess, updatedAt: Date.now(), messages: [...sess.messages, ...additions].slice(-MAX_MESSAGES_PER_SESSION) }
          })
        })
      } catch { /* ignore in browser */ }
    }
    void pullPetTurns()
    const onVis = () => { if (document.visibilityState === 'visible') void pullPetTurns() }
    const onFocus = () => { void pullPetTurns() }
    const pollTimer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void pullPetTurns()
    }, 2500)
    document.addEventListener('visibilitychange', onVis)
    window.addEventListener('focus', onFocus)
    return () => {
      window.clearInterval(pollTimer)
      window.removeEventListener('whale-miniapp-ask', onMiniAppAsk)
      document.removeEventListener('visibilitychange', onVis)
      window.removeEventListener('focus', onFocus)
    }
  }, [bridge, activeId])

  useEffect(() => {
    const pollWorkers = async () => {
      try {
        const res = await bridge.companionDashboard?.({ operation: 'workerList' })
        const rawList = Array.isArray(res?.workers) ? res.workers : (Array.isArray(res?.items) ? res.items : [])
        const mapped = (rawList as Array<Record<string, unknown>>)
          .map(w => ({
            id: typeof w.id === 'string' ? w.id : '',
            title: (typeof w.title === 'string' ? w.title : (typeof w.task === 'string' ? w.task : '后台任务')).slice(0, 26),
            status: typeof w.status === 'string' ? w.status : '',
            attention: typeof w.attention === 'string' ? w.attention : (typeof w.question === 'string' ? w.question : ''),
          }))
          .filter(w => w.id && ['running', 'queued', 'blocked', 'needs_input', 'attention'].includes(w.status))
        setActiveWorkers(mapped)
      } catch { /* ignore */ }
    }
    void pollWorkers()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void pollWorkers()
    }, 6000)
    return () => window.clearInterval(timer)
  }, [bridge])

  const cancelCurrentTurn = async (): Promise<void> => {
    const reqId = activeRequestRef.current
    if (!reqId) return
    pendingFollowUpRef.current = []
    setStreamingPhase('cancelling')
    try {
      await bridge.whaleNativeChatCancel?.({ requestId: reqId })
    } catch {
      // Ignore cancel bridge errors.
    }
  }

  const extractVideoKeyframes = async (file: File): Promise<{ frames: string[]; durationSec: number }> => {
    const url = URL.createObjectURL(file)
    try {
      const video = document.createElement('video')
      video.src = url
      video.muted = true
      video.playsInline = true
      await new Promise<void>((resolve, reject) => {
        video.onloadedmetadata = () => resolve()
        video.onerror = () => reject(new Error('video load failed'))
      })
      const duration = Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 1
      const points = [0.1, 0.5, 0.85].map(r => Math.min(Math.max(0.05, duration * r), Math.max(0.05, duration - 0.05)))
      const canvas = document.createElement('canvas')
      const scale = Math.min(1, 720 / Math.max(video.videoWidth || 720, 1))
      canvas.width = Math.max(160, Math.round((video.videoWidth || 720) * scale))
      canvas.height = Math.max(120, Math.round((video.videoHeight || 480) * scale))
      const ctx = canvas.getContext('2d')
      const frames: string[] = []
      for (const sec of points) {
        video.currentTime = sec
        await new Promise<void>(resolve => {
          video.onseeked = () => resolve()
          setTimeout(resolve, 600)
        })
        if (ctx) {
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
          frames.push(canvas.toDataURL('image/jpeg', 0.78))
        }
      }
      return { frames, durationSec: Math.round(duration) }
    } finally {
      URL.revokeObjectURL(url)
    }
  }

  const handleImagePick = async (files: FileList | null): Promise<void> => {
    if (!files || files.length === 0) return
    const picked: string[] = []
    const uploadedDocs: Array<{ fileName: string; absolutePath: string; bytes: number }> = []
    let videoHint = ''
    for (const file of Array.from(files).slice(0, 4)) {
      try {
        if (file.type.startsWith('video/')) {
          const { frames, durationSec } = await extractVideoKeyframes(file)
          picked.push(...frames)
          videoHint = `请分析这段视频（时长约 ${durationSec} 秒，已自动抽取 3 张关键帧）`
        } else if (file.type.startsWith('image/')) {
          const dataUrl = await compressImageToDataUrl(file)
          if (dataUrl) picked.push(dataUrl)
        } else if (file.size <= 15 * 1024 * 1024) {
          const dataUrl = await new Promise<string>((resolve, reject) => {
            const reader = new FileReader()
            reader.onerror = () => reject(new Error('读取文档失败'))
            reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
            reader.readAsDataURL(file)
          })
          if (dataUrl && bridge.companionDashboard) {
            const res = await bridge.companionDashboard({
              operation: 'uploadChatFile',
              fileName: file.name,
              base64: dataUrl,
            }) as { ok?: boolean; fileName?: string; absolutePath?: string; bytes?: number }
            if (res?.ok && res.absolutePath) {
              uploadedDocs.push({
                fileName: res.fileName || file.name,
                absolutePath: res.absolutePath,
                bytes: Number(res.bytes || file.size),
              })
            }
          }
        }
      } catch {
        // Ignore single broken file.
      }
    }
    if (picked.length > 0) {
      setPendingImages(prev => [...prev, ...picked].slice(0, 3))
      if (videoHint && !input.trim()) setInput(videoHint)
    }
    if (uploadedDocs.length > 0) {
      setPendingFiles(prev => [...prev, ...uploadedDocs].slice(0, 4))
    }
  }

  const sendText = async (raw: string, overrideImages?: string[]): Promise<void> => {
    const attachedImages = overrideImages ?? pendingImages
    const attachedDocs = overrideImages ? [] : pendingFiles
    const baseText = raw.trim() || (
      attachedDocs.length > 0
        ? t('请读取并分析我上传的文档，提炼核心要点。')
        : (attachedImages.length > 0 ? t('请帮我看看这张图并给出实用建议。') : '')
    )
    if (!baseText) return
    const docHeader = attachedDocs.length > 0
      ? attachedDocs.map(d => `[已上传文档：uploads/${d.fileName}，路径：${d.absolutePath}，大小：${Math.max(1, Math.round(d.bytes / 1024))}KB]`).join('\n') + '\n'
      : ''
    const displayText = attachedDocs.length > 0
      ? `${attachedDocs.map(d => `📎 ${d.fileName}`).join(' · ')}\n${baseText}`
      : baseText
    const text = `${docHeader}${baseText}`
    const targetSessionId = currentSession.id

    // 运行中允许随时插话、补充或修正：不锁输入框，立即实时注入正在运行的 Agent 引擎
    if (sending && activeRequestRef.current) {
      const steerMsg: WhaleChatMessage = {
        id: `u-${Date.now()}-${Math.random().toString(36).slice(2, 5)}`,
        role: 'user',
        text,
        steered: true,
      }
      updateSessionMessages(targetSessionId, prev => [...prev, steerMsg], text)
      setInput('')
      setSlashOpen(false)
      setStreamingPhase('steering')
      const reqId = activeRequestRef.current
      let steeredLive = false
      if (bridge.whaleNativeChatSteer) {
        try {
          const res = await bridge.whaleNativeChatSteer({ requestId: reqId, message: text })
          steeredLive = Boolean(res?.steered)
        } catch {
          steeredLive = false
        }
      }
      if (!steeredLive) {
        pendingFollowUpRef.current.push(text)
      }
      return
    }

    const requestId = `req-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
    const userMsg: WhaleChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      text: displayText,
      images: attachedImages.length > 0 ? attachedImages : undefined,
    }
    const nextHistory = [...messages, userMsg]
    autoFollowRef.current = true
    setShowScrollBottom(false)
    updateSessionMessages(targetSessionId, prev => [...prev, userMsg], baseText)
    setInput('')
    setPendingImages([])
    setPendingFiles([])
    setSlashOpen(false)
    setSending(true)
    setStreamingText('')
    streamingReasoningRef.current = ''
    reasoningStartRef.current = 0
    setStreamingReasoning('')
    setStreamingSteps([])
    setStreamingPhase('thinking')
    activeRequestRef.current = requestId
    try {
      const historyPayload = nextHistory.slice(-14).map(m => ({ role: m.role, text: m.text }))
      const result = await bridge.whaleNativeChat({
        message: text,
        history: historyPayload.slice(0, -1),
        sessionId: targetSessionId,
        requestId,
        images: attachedImages.length > 0 ? attachedImages : undefined,
      })
      updateSessionMessages(targetSessionId, prev => [
        ...prev,
        {
          id: `a-${Date.now()}`,
          role: 'assistant',
          text: result.reply,
          steps: result.steps,
          model: result.model,
          elapsedMs: result.elapsedMs,
          compressionRatio: result.compressionRatio,
          savedChars: result.savedChars,
          cacheHitRatio: result.cacheHitRatio,
          reasoning: streamingReasoningRef.current.trim() || undefined,
          reasoningMs: reasoningStartRef.current > 0 ? Math.max(500, Date.now() - reasoningStartRef.current) : undefined,
          suggestions: result.suggestions,
        },
      ])
      if (!result.cancelled && result.reply.trim()) {
        void bridge.companionDashboard?.({
          operation: 'sharedChatAppend',
          userText: text,
          assistantText: result.reply,
          source: 'app',
        }).catch(() => {})
      }
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      updateSessionMessages(targetSessionId, prev => [
        ...prev,
        {
          id: `e-${Date.now()}`,
          role: 'assistant',
          text: reason || t('暂时无法连接模型服务，请检查「我的 → 模型与密钥」配置。'),
          error: true,
        },
      ])
    } finally {
      activeRequestRef.current = null
      setStreamingText('')
      streamingReasoningRef.current = ''
      reasoningStartRef.current = 0
      setStreamingReasoning('')
      setStreamingSteps([])
      setStreamingPhase('')
      setSending(false)
      if (pendingFollowUpRef.current.length > 0) {
        const queued = pendingFollowUpRef.current.join('\n')
        pendingFollowUpRef.current = []
        window.setTimeout(() => { void sendText(queued) }, 40)
      }
    }
  }

  const copyReply = (id: string, text: string): void => {
    void navigator.clipboard?.writeText(text).then(() => {
      setCopiedId(id)
      window.setTimeout(() => setCopiedId(current => (current === id ? null : current)), 1500)
    }).catch(() => {})
  }


  sendTextRef.current = sendText

  const toggleVoiceRecord = async (): Promise<void> => {
    if (transcribing) return
    if (recording) {
      setRecording(false)
      setTranscribing(true)
      setVoiceNote('__transcribing__')
      try {
        const res = await bridge.stopVoiceRecording?.()
        if (res?.ok && res.text?.trim()) {
          setVoiceNote('')
          await sendText(res.text.trim())
        } else {
          setVoiceNote(res?.error || t('语音识别失败，请确认已在设置中配置硅基流动语音 Key'))
        }
      } catch (err) {
        setVoiceNote(err instanceof Error ? err.message : t('语音识别失败'))
      } finally {
        setTranscribing(false)
      }
      return
    }
    if (!bridge.startVoiceRecording) {
      setVoiceNote(t('当前环境不支持原生录音，请在 Android 客户端中使用'))
      return
    }
    void bridge.stopVoiceSpeaking?.().catch(() => {})
    try {
      const res = await bridge.startVoiceRecording()
      if (res.ok) {
        setRecording(true)
        setVoiceNote('__recording__')
      } else {
        setVoiceNote(res.error || t('无法启动录音，请先授予麦克风权限'))
      }
    } catch (err) {
      setVoiceNote(err instanceof Error ? err.message : t('无法启动录音'))
    }
  }

  useEffect(() => {
    if (!sending) {
      setWaitElapsedSec(0)
      return
    }
    const t0 = Date.now()
    const secTimer = window.setInterval(() => {
      setWaitElapsedSec(Math.floor((Date.now() - t0) / 1000))
    }, 500)
    return () => {
      window.clearInterval(secTimer)
    }
  }, [sending])

  const phaseMeta = useMemo(() => {
    const raw = (streamingPhase || '').trim()
    if (raw.includes('|')) {
      const sep = raw.indexOf('|')
      const category = raw.slice(0, sep) || 'thinking'
      const text = raw.slice(sep + 1).trim()
      if (text) return { category, text }
    }
    if (raw === 'steering' || raw === 'restarting') return { category: 'thinking', text: t('收到中途补充，正在调整当前方案') }
    if (raw === 'cancelling') return { category: 'thinking', text: t('正在中止当前执行') }
    const lastStep = streamingSteps[streamingSteps.length - 1]
    if (lastStep) return { category: 'analyzing', text: t('分析「{0}」结果，判断下一步', lastStep.label.slice(0, 22)) }
    return { category: 'thinking', text: t('理解你的问题并规划步骤') }
  }, [streamingPhase, streamingSteps])
  const phaseLabel = phaseMeta.text

  const renderReceiptIcon = (kind: ActionReceipt['kind']) => {
    if (style !== 'simple') {
      if (kind === 'secretary') return <CuteIcon name="notifications" size={18} />
      if (kind === 'knowledge') return <CuteIcon name="memory" size={18} />
      if (kind === 'memory') return <CuteIcon name="assistant" size={18} />
      return <CuteIcon name="workshop" size={18} />
    }
    if (kind === 'secretary') return <BellRing size={14} aria-hidden="true" />
    if (kind === 'knowledge') return <BookmarkCheck size={14} aria-hidden="true" />
    if (kind === 'memory') return <Brain size={14} aria-hidden="true" />
    if (kind === 'file') return <FileText size={14} aria-hidden="true" />
    return <Hammer size={14} aria-hidden="true" />
  }

  return (
    <section className="whale-chat-screen" aria-label={t('鲸鱼助手对话')}>
      <header className="whale-chat-header is-minimal">
        <div className="whale-chat-brand">
          <img src={activeAvatar} alt="" className="whale-chat-brand-avatar" />
          <span className="whale-chat-brand-name">{isUsagi ? t('乌萨奇') : t('鲸鱼娘')}</span>
        </div>
        <div className="whale-chat-shortcuts" aria-label={t('常用功能入口')}>
          <button
            type="button"
            className={`whale-icon-chip${workspaceOpen ? ' is-active' : ''}`}
            title={t('AI 工作目录')}
            aria-label={t('AI 工作目录')}
            onClick={() => setWorkspaceOpen(true)}
          >
            {style !== 'simple' ? <CuteIcon name="workspace" size={20} /> : <FolderOpen size={17} />}
          </button>
          <button
            type="button"
            className={`whale-icon-chip${sessionsOpen ? ' is-active' : ''}`}
            title={t('会话列表')}
            aria-label={t('会话列表')}
            aria-expanded={sessionsOpen}
            onClick={() => setSessionsOpen(open => !open)}
          >
            {style !== 'simple' ? <CuteIcon name="chat" size={21} /> : <MessagesSquare size={17} />}
          </button>
          <button
            type="button"
            className="whale-icon-chip"
            title={t('新对话')}
            aria-label={t('新对话')}
            onClick={startNewSession}
          >
            {style !== 'simple' ? <CuteIcon name="new-chat" size={21} /> : <MessageSquarePlus size={17} />}
          </button>
        </div>
      </header>

      {activeWorkers.length > 0 && (
        <div className="whale-worker-capsule" role="status">
          <span className="whale-worker-capsule-dot" aria-hidden="true" />
          <span className="whale-worker-capsule-text">
            {activeWorkers.find(w => w.status === 'blocked' || w.status === 'needs_input' || w.status === 'attention')
              ? `后台任务待确认：${activeWorkers.find(w => w.status === 'blocked' || w.status === 'needs_input' || w.status === 'attention')!.attention || activeWorkers[0].title}`
              : `${activeWorkers.length} 个后台任务运行中 · ${activeWorkers[0].title}`}
          </span>
          <button
            type="button"
            className="whale-worker-capsule-btn"
            onClick={() => onNavigate('assistant-tasks')}
          >
            {t('查看')}
          </button>
        </div>
      )}

      {sessionsOpen && (
        <aside className="whale-sessions-drawer" aria-label={t('本地会话管理')}>
          <div className="whale-sessions-head">
            <strong>{t('对话会话')} ({sessions.length})</strong>
            <button type="button" className="whale-session-new-btn" disabled={sending} onClick={startNewSession}>
              <MessageSquarePlus size={14} aria-hidden="true" />
              {t('新建会话')}
            </button>
          </div>
          <ul className="whale-sessions-list">
            {sessions.map(session => {
              const active = session.id === currentSession.id
              return (
                <li key={session.id} className={active ? 'is-active' : ''}>
                  {renamingId === session.id ? (
                    <form
                      className="whale-session-rename"
                      onSubmit={event => {
                        event.preventDefault()
                        commitRename(session.id)
                      }}
                    >
                      <input
                        type="text"
                        value={renameDraft}
                        aria-label={t('输入会话名称')}
                        autoFocus
                        onChange={event => setRenameDraft(event.target.value)}
                        onBlur={() => commitRename(session.id)}
                      />
                    </form>
                  ) : (
                    <>
                      <button
                        type="button"
                        className="whale-session-select"
                        disabled={sending}
                        onClick={() => {
                          setActiveId(session.id)
                          setSessionsOpen(false)
                        }}
                      >
                        <span>{session.title}</span>
                        <small>{session.messages.length} {t('条')}</small>
                      </button>
                      <button
                        type="button"
                        className="whale-session-action"
                        aria-label={t('重命名会话')}
                        title={t('重命名会话')}
                        onClick={() => {
                          setRenamingId(session.id)
                          setRenameDraft(session.title)
                        }}
                      >
                        <Pencil size={13} />
                      </button>
                      <button
                        type="button"
                        className="whale-session-action"
                        aria-label={t('删除会话')}
                        title={t('删除会话')}
                        disabled={sending}
                        onClick={() => deleteSession(session.id)}
                      >
                        <Trash2 size={13} />
                      </button>
                    </>
                  )}
                </li>
              )
            })}
          </ul>
        </aside>
      )}

      <div
        className="whale-chat-stream"
        ref={listRef}
        onScroll={event => {
          if (programmaticScrollRef.current) return
          const el = event.currentTarget
          const dist = el.scrollHeight - (el.scrollTop + el.clientHeight)
          autoFollowRef.current = dist <= 120
          setShowScrollBottom(dist > 200)
        }}
        onClick={event => {
          const target = event.target as HTMLElement | null
          const copyBtn = target?.closest?.('.whale-code-copy-btn') as HTMLButtonElement | null
          if (copyBtn) {
            const rawCode = decodeURIComponent(copyBtn.getAttribute('data-code') || '')
            if (rawCode && navigator.clipboard?.writeText) {
              void navigator.clipboard.writeText(rawCode).then(() => {
                copyBtn.textContent = '已复制'
                window.setTimeout(() => { copyBtn.textContent = '复制代码' }, 1400)
              }).catch(() => {})
            }
          }
        }}
      >
        {messages.length === 0 ? (
          <div className="whale-chat-empty">
            <div className="whale-hero-stage">
              <img
                src={heroMark}
                alt=""
                width={style === 'simple' ? 56 : 88}
                height={style === 'simple' ? 56 : 88}
                className="whale-chat-hero-mark"
              />
            </div>
            <span className="whale-cute-greeting">{isUsagi ? t('呀哈！乌萨奇一直都在哦～') : t('主人，小鲸鱼一直都在哦～')}</span>
            <h2>{t('今天想一起做点什么？')}</h2>
            <div className="whale-quick-hub" aria-label={t('快捷空间')}>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('assistant-memory')}>
                {style !== 'simple' ? <CuteIcon name="memory" size={19} /> : <BookOpen size={14} aria-hidden="true" />}
                {t('记忆与资料')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('assistant-tasks')}>
                {style !== 'simple' ? <CuteIcon name="tasks" size={19} /> : <ListTodo size={14} aria-hidden="true" />}
                {t('事项与日程')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('tools')}>
                {style !== 'simple' ? <CuteIcon name="workshop" size={19} /> : <Hammer size={14} aria-hidden="true" />}
                {t('功能工坊')}
              </button>
            </div>
            <div className="whale-starter-grid">
              {buildTimeAwareStarters().map(item => (
                <button
                  key={item.label}
                  type="button"
                  className="whale-starter-card"
                  disabled={sending}
                  onClick={() => { void sendText(item.text) }}
                >
                  {style !== 'simple' && <CuteIcon name={item.icon} size={28} className="whale-starter-icon" />}
                  <strong>{t(item.label)}</strong>
                  <span>{item.text}</span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map((msg, msgIdx) => {
            const receipts = msg.role === 'assistant' && !msg.error ? extractActionReceipts(msg.steps) : []
            return (
              <article key={msg.id} className={`whale-msg whale-msg-${msg.role}${msg.error ? ' is-error' : ''}`}>
                {msg.steps && msg.steps.length > 0 && (
                  <details className="whale-msg-steps">
                    <summary>
                      {style !== 'simple' ? <CuteIcon name="workshop" size={16} /> : <Wrench size={13} aria-hidden="true" />}
                      {style !== 'simple' ? `×${msg.steps.length}` : t('已调用 {0} 项原生能力', msg.steps.length)}
                    </summary>
                    <ul>
                      {msg.steps.map((step, i) => (
                        <li key={`${step.tool}-${i}`}>
                          <CheckCircle2 size={13} aria-hidden="true" />
                          <span>{step.label}</span>
                          <small>{step.durationMs}ms</small>
                        </li>
                      ))}
                    </ul>
                  </details>
                )}
                {msg.role === 'assistant' && !msg.error ? (
                  <div className="whale-msg-row">
                    <img src={activeAvatar} alt="" width={30} height={30} className="whale-msg-inline-avatar" />
                    <div className="whale-msg-col">
                      {msg.reasoning && (
                        <details className="whale-reasoning-box">
                          <summary>
                            {msg.reasoningMs ? `已深度思考 ${(msg.reasoningMs / 1000).toFixed(1)}s` : '查看深度思考过程'}
                          </summary>
                          <div className="whale-reasoning-body">{msg.reasoning}</div>
                        </details>
                      )}
                      {(() => {
                        const extracted = extractWhaleWidgets(msg.text, false)
                        return (
                          <>
                            {extracted.cleanMarkdown && (
                              <div
                                className="whale-msg-bubble whale-markdown"
                                dangerouslySetInnerHTML={{ __html: renderCachedMarkdown(msg.id, extracted.cleanMarkdown) }}
                              />
                            )}
                            {extracted.widgets.map((wSpec, wIdx) => (
                              <ChatWidgetCard
                                key={`${msg.id}-w-${wIdx}`}
                                spec={wSpec}
                                storageKey={`${msg.id}-${wIdx}`}
                                onSaveToWorkshop={prompt => { void sendText(prompt) }}
                                onSendAction={prompt => { void sendText(prompt) }}
                              />
                            ))}
                          </>
                        )
                      })()}
                    </div>
                  </div>
                ) : (
                  <div className="whale-msg-bubble">
                    {msg.images && msg.images.length > 0 && (
                      <div className="whale-msg-images">
                        {msg.images.map((src, i) => (
                          <img key={`${msg.id}-img-${i}`} src={src} alt="" className="whale-msg-thumb" />
                        ))}
                      </div>
                    )}
                    {msg.text}
                    {msg.role === 'user' && !sending && (
                      <button
                        type="button"
                        className="whale-user-edit-btn"
                        aria-label={t('编辑重发')}
                        title={t('编辑重发')}
                        onClick={() => {
                          setInput(msg.text)
                          inputRef.current?.focus()
                        }}
                      >
                        <Pencil size={11} />
                      </button>
                    )}
                    {msg.error && (
                      <div className="whale-receipt-btns" style={{ marginTop: 8 }}>
                        {(msg.text.includes('API Key') || msg.text.includes('模型与密钥')) && (
                          <button
                            type="button"
                            className="whale-receipt-btn is-primary"
                            onClick={() => onNavigate('settings-models')}
                          >
                            {t('前往配置模型与密钥')}
                          </button>
                        )}
                        {(() => {
                          const lastUser = [...messages].reverse().find(m => m.role === 'user')
                          return lastUser ? (
                            <button
                              type="button"
                              className="whale-receipt-btn"
                              disabled={sending}
                              onClick={() => { void sendText(lastUser.text) }}
                            >
                              <RotateCcw size={13} aria-hidden="true" />
                            </button>
                          ) : null
                        })()}
                      </div>
                    )}
                  </div>
                )}
                {receipts.some(r => r.kind === 'file' && r.fileName) && (
                  <div className="whale-wx-media-stack" aria-label={t('文件与图片')}>
                    {receipts.filter(r => r.kind === 'file' && r.fileName).map(receipt => (
                      <ChatMediaCard key={receipt.key} bridge={bridge} fileName={receipt.fileName!} />
                    ))}
                  </div>
                )}
                {receipts.some(r => r.kind !== 'file') && (
                  <div className="whale-receipt-list" aria-label={t('操作回执')}>
                    {receipts.filter(r => r.kind !== 'file').map(receipt => (
                      <div key={receipt.key} className="whale-receipt-card">
                        <span className="whale-receipt-title">
                          {renderReceiptIcon(receipt.kind)}
                          {t(receipt.title)}
                        </span>
                        <div className="whale-receipt-btns">
                          {receipt.followUpPrompt && receipt.followUpLabel && (
                            <button
                              type="button"
                              className="whale-receipt-btn"
                              disabled={sending}
                              onClick={() => { void sendText(receipt.followUpPrompt!) }}
                            >
                              {t(receipt.followUpLabel)}
                            </button>
                          )}
                          {receipt.kind === 'file' && receipt.fileName ? (
                            <>
                              <button
                                type="button"
                                className="whale-receipt-btn"
                                onClick={() => {
                                  void bridge.companionDashboard?.({ operation: 'shareExportedFile', fileName: receipt.fileName }).catch(() => {})
                                }}
                              >
                                <Share2 size={12} aria-hidden="true" />
                                {t('分享')}
                              </button>
                              <button
                                type="button"
                                className="whale-receipt-btn is-primary"
                                onClick={() => {
                                  void bridge.companionDashboard?.({ operation: 'openExportedFile', fileName: receipt.fileName }).catch(() => {})
                                }}
                              >
                                <ExternalLink size={12} aria-hidden="true" />
                                {t('打开')}
                              </button>
                            </>
                          ) : (
                            <button
                              type="button"
                              className="whale-receipt-btn is-primary"
                              onClick={() => onNavigate(receipt.view)}
                            >
                              {t(receipt.actionLabel)}
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {msg.role === 'assistant' && !msg.error && (
                  <footer className="whale-msg-meta">
                    <span>
                      {msg.model ?? 'whale'}
                      {msg.elapsedMs ? ` · ${(msg.elapsedMs / 1000).toFixed(1)}s` : ''}
                      {typeof msg.compressionRatio === 'number' && msg.compressionRatio > 0 ? ` · ${t('压缩')} ${Math.round(msg.compressionRatio * 100)}%` : ''}
                      {typeof msg.cacheHitRatio === 'number' && msg.cacheHitRatio >= 0 ? ` · ${t('缓存')} ${Math.round(msg.cacheHitRatio * 100)}%` : ''}
                    </span>
                    <button
                      type="button"
                      className="whale-copy-btn"
                      aria-label={t('复制回复')}
                      onClick={() => copyReply(msg.id, msg.text)}
                    >
                      {copiedId === msg.id ? <Check size={13} /> : <Copy size={13} />}
                    </button>
                    <button
                      type="button"
                      className="whale-copy-btn"
                      aria-label={t('重新生成')}
                      title={t('重新生成')}
                      disabled={sending}
                      onClick={() => {
                        const prevUser = [...messages.slice(0, msgIdx)].reverse().find(m => m.role === 'user')
                        if (prevUser) void sendText(prevUser.text)
                      }}
                    >
                      <RotateCcw size={12} />
                    </button>

                  </footer>
                )}
                {msg.role === 'assistant' && !msg.error && msg.suggestions && msg.suggestions.length > 0 && msg.id === messages[messages.length - 1]?.id && (
                  <div className="whale-next-suggestions" aria-label={t('建议下一步')}>
                    {msg.suggestions.map((sug, idx) => (
                      <button
                        key={`${msg.id}-sug-${idx}`}
                        type="button"
                        className="whale-next-chip"
                        onClick={() => { void sendText(sug) }}
                      >
                        {sug}
                      </button>
                    ))}
                  </div>
                )}
              </article>
            )
          })
        )}
        {sending && (
          <div className="whale-msg whale-msg-assistant" role="status">
            {!streamingText && (
              <div className="whale-grok-status-row" title={phaseLabel}>
                <span className="whale-grok-status-icon" aria-hidden="true"><AgentStatusIcon style={style} category={streamingReasoning && !streamingText ? 'reasoning' : phaseMeta.category} /></span>
                <span className="whale-grok-status-label">{phaseLabel}</span>
                {waitElapsedSec > 0 && <span className="whale-grok-status-sec">{waitElapsedSec}s</span>}
              </div>
            )}
            {streamingSteps.length > 0 && (
              <div className="whale-msg-steps">
                <ul>
                  {streamingSteps.map((step, i) => (
                    <li key={`${step.tool}-${i}`}>
                      <CheckCircle2 size={13} aria-hidden="true" />
                      <span>{step.label}</span>
                      <small>{step.durationMs}ms</small>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {streamingReasoning && (
              <details className="whale-reasoning-box is-live" open={!streamingText}>
                <summary>{streamingText ? '深度思考完成' : '深度思考中…'}</summary>
                <div className="whale-reasoning-body">{streamingReasoning}</div>
              </details>
            )}
            {streamingText && (
              <div
                className="whale-msg-bubble whale-markdown whale-streaming"
                dangerouslySetInnerHTML={{ __html: md.render(stripStreamingMarker(streamingText)) }}
              />
            )}
          </div>
        )}
      </div>

      {(showArchiveHint || slashOpen) && (
        <div className="whale-composer-assist" aria-label={t('快捷指令与归档建议')}>
          {showArchiveHint && (
            <button
              type="button"
              className="whale-assist-pill is-accent"
              aria-label={t('顺手存入资料库并总结')}
              title={t('顺手存入资料库并总结')}
              onClick={() => {
                void sendText(`帮我把下面内容存入本地资料库并提炼核心要点：\n\n${input.trim()}`)
              }}
            >
              {style !== 'simple' ? <CuteIcon name="memory" size={19} /> : <BookmarkCheck size={14} aria-hidden="true" />}
            </button>
          )}
          {slashOpen && QUICK_SLASH_ACTIONS.map(act => (
            <button
              key={act.id}
              type="button"
              className="whale-assist-pill"
              disabled={sending}
              onClick={() => {
                if (act.sendNow) {
                  void sendText(act.prefix)
                } else {
                  setInput(act.prefix)
                  setSlashOpen(false)
                  inputRef.current?.focus()
                }
              }}
            >
              {t(act.label)}
            </button>
          ))}
        </div>
      )}

      {voiceNote && (
        <div
          className={`whale-voice-status${recording ? ' is-recording' : ''}`}
          role="status"
          aria-label={voiceNote === '__recording__' ? t('正在听你说话') : voiceNote === '__transcribing__' ? t('正在识别语音') : voiceNote}
        >
          {voiceNote === '__recording__' ? (
            <span className="whale-voice-wave" aria-hidden="true"><i /><i /><i /><i /></span>
          ) : voiceNote === '__transcribing__' ? (
            <span className="whale-typing-dots" aria-hidden="true"><i /><i /><i /></span>
          ) : (
            <span>{voiceNote}</span>
          )}
        </div>
      )}

      {showScrollBottom && (
        <button
          type="button"
          className="whale-scroll-bottom-btn"
          aria-label={t('回到底部')}
          onClick={() => {
            autoFollowRef.current = true
            setShowScrollBottom(false)
            if (listRef.current) {
              listRef.current.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
            }
          }}
        >
          <ArrowDown size={15} />
          <span>{t('回到底部')}</span>
        </button>
      )}

      {pendingFiles.length > 0 && (
        <div className="whale-file-chip-row" aria-label={t('待发送文档')}>
          {pendingFiles.map((f, idx) => (
            <span key={`pending-doc-${idx}`} className="whale-file-chip">
              <FileText size={13} aria-hidden="true" />
              <span className="whale-file-chip-name">{f.fileName}</span>
              <button
                type="button"
                aria-label={t('移除文档')}
                onClick={() => setPendingFiles(prev => prev.filter((_, i) => i !== idx))}
              >
                <X size={11} />
              </button>
            </span>
          ))}
        </div>
      )}

      {pendingImages.length > 0 && (
        <div className="whale-image-preview-row" aria-label={t('待发送图片')}>
          {pendingImages.map((src, idx) => (
            <div key={`pending-img-${idx}`} className="whale-image-preview-item">
              <img src={src} alt="" />
              <button
                type="button"
                className="whale-image-remove-btn"
                aria-label={t('移除图片')}
                onClick={() => setPendingImages(prev => prev.filter((_, i) => i !== idx))}
              >
                <X size={11} />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="whale-chat-composer-wrap"><form
        className="whale-chat-composer"
        onSubmit={event => {
          event.preventDefault()
          void sendText(input)
        }}
      >
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*,video/*,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.csv,.json,.py,.js,.ts,.tsx,.kt,.java,.c,.cpp,.h,.v,.sv,.html,.xml,.yaml,.yml,.log,.zip"
          multiple
          hidden
          onChange={event => {
            void handleImagePick(event.target.files)
            event.target.value = ''
          }}
        />
        <button
          type="button"
          className={`whale-slash-btn${slashOpen ? ' is-active' : ''}`}
          aria-label={t('快捷指令')}
          title={t('快捷指令')}
          aria-expanded={slashOpen}
          disabled={sending}
          onClick={() => setSlashOpen(open => !open)}
        >
          {style !== 'simple' ? <CuteIcon name="slash" size={20} /> : <Slash size={15} />}
        </button>
        <button
          type="button"
          className="whale-img-btn"
          aria-label={t('发送图片或视频')}
          title={t('发送图片或视频')}
          onClick={() => fileInputRef.current?.click()}
        >
          {style !== 'simple' ? <CuteIcon name="appearance" size={20} /> : <ImagePlus size={16} />}
        </button>
        <input
          ref={inputRef}
          type="text"
          value={input}
          placeholder={isUsagi ? t('和乌萨奇聊聊…') : t('和鲸鱼娘聊聊…')}
          aria-label={t('输入消息')}
          onChange={event => {
            const val = event.target.value
            setInput(val)
            if (val === '/') setSlashOpen(true)
            else if (val === '') setSlashOpen(false)
          }}
        />
        <button
          type="button"
          className={`whale-mic-btn${recording ? ' is-recording' : ''}`}
          disabled={transcribing}
          aria-label={recording ? t('停止录音并发送') : t('语音输入')}
          title={recording ? t('停止录音并发送') : t('语音输入')}
          onClick={() => { void toggleVoiceRecord() }}
        >
          {recording ? <Square size={14} /> : (style !== 'simple' ? <CuteIcon name="mic" size={21} /> : <Mic size={16} />)}
        </button>
        {sending && input.trim() === '' && pendingImages.length === 0 ? (
          <button
            type="button"
            className="whale-send-btn is-stop"
            aria-label={t('停止生成')}
            title={t('停止生成')}
            onClick={() => { void cancelCurrentTurn() }}
          >
            <Square size={14} />
          </button>
        ) : (
          <button
            type="submit"
            className={`whale-send-btn${sending ? ' is-steer' : ''}`}
            disabled={input.trim() === '' && pendingImages.length === 0 && pendingFiles.length === 0}
            aria-label={t('发送')}
            title={t('发送')}
          >
            {style !== 'simple' ? <CuteIcon name="send" size={21} /> : <ArrowUp size={18} />}
          </button>
        )}
      </form></div>
      {workspaceOpen && <AiWorkspaceModal bridge={bridge} onClose={() => setWorkspaceOpen(false)} />}
    </section>
  )
}
