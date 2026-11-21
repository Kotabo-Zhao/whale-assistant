import { useEffect, useMemo, useRef, useState } from 'react'
import MarkdownIt from 'markdown-it'
import {
  ArrowUp,
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
  Volume2,
  VolumeX,
  Wrench,
  X,
} from 'lucide-react'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanAvatar from '../assets/whale-chan-avatar.png'
import { CuteIcon, type CuteIconName } from '../components/CuteIcon'
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
  kind: 'secretary' | 'knowledge' | 'memory' | 'workshop'
  title: string
  view: AppView
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
}

export function WhaleChatScreen({ bridge, onNavigate }: WhaleChatScreenProps) {
  const style = useInterfaceStyle()
  const heroMark = style === 'simple' ? grokMark : whaleChanAvatar
  const [sessions, setSessions] = useState<WhaleChatSession[]>(loadSessions)
  const [activeId, setActiveId] = useState<string>(() => loadActiveSessionId(loadSessions()))
  const [sessionsOpen, setSessionsOpen] = useState(false)
  const [slashOpen, setSlashOpen] = useState(false)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [input, setInput] = useState('')
  const [pendingImages, setPendingImages] = useState<string[]>([])
  const [sending, setSending] = useState(false)
  const [streamingText, setStreamingText] = useState('')
  const [streamingSteps, setStreamingSteps] = useState<WhaleNativeChatStep[]>([])
  const [streamingPhase, setStreamingPhase] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [recording, setRecording] = useState(false)
  const [transcribing, setTranscribing] = useState(false)
  const [voiceNote, setVoiceNote] = useState('')
  const [autoSpeak, setAutoSpeak] = useState<boolean>(() => {
    try {
      return window.localStorage.getItem('whale.mainChatAutoSpeak.v1') === 'true'
    } catch {
      return false
    }
  })
  const activeRequestRef = useRef<string | null>(null)
  const pendingFollowUpRef = useRef<string[]>([])
  const listRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const md = useMemo(() => new MarkdownIt({ html: false, linkify: true, breaks: true }), [])
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
  useEffect(() => {
    const el = listRef.current
    if (!el) return
    if (autoFollowRef.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [messages, streamingText, streamingSteps])

  useEffect(() => {
    if (!bridge.addWhaleChatListener) return
    let disposed = false
    let cleanup: (() => Promise<void>) | undefined
    void bridge.addWhaleChatListener(event => {
      if (disposed || !activeRequestRef.current || event.requestId !== activeRequestRef.current) return
      if (event.type === 'delta' && event.text) {
        setStreamingText(prev => prev + event.text)
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
    if (sending) return
    const fresh = createSession()
    setSessions(prev => [fresh, ...prev].slice(0, MAX_SESSIONS))
    setActiveId(fresh.id)
    setSessionsOpen(false)
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

  const sendTextRef = useRef<(raw: string) => Promise<void>>(async () => {})
  useEffect(() => {
    const onMiniAppAsk = (ev: Event) => {
      const prompt = (ev as CustomEvent<string>).detail
      if (typeof prompt === 'string' && prompt.trim()) {
        void sendTextRef.current(prompt.trim())
      }
    }
    window.addEventListener('whale-miniapp-ask', onMiniAppAsk)
    return () => window.removeEventListener('whale-miniapp-ask', onMiniAppAsk)
  }, [])

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

  const handleImagePick = async (files: FileList | null): Promise<void> => {
    if (!files || files.length === 0) return
    const picked: string[] = []
    for (const file of Array.from(files).slice(0, 3)) {
      try {
        const dataUrl = await compressImageToDataUrl(file)
        if (dataUrl) picked.push(dataUrl)
      } catch {
        // Ignore single broken file.
      }
    }
    if (picked.length > 0) {
      setPendingImages(prev => [...prev, ...picked].slice(0, 3))
    }
  }

  const sendText = async (raw: string, overrideImages?: string[]): Promise<void> => {
    const attachedImages = overrideImages ?? pendingImages
    const text = raw.trim() || (attachedImages.length > 0 ? t('请帮我看看这张图并给出实用建议。') : '')
    if (!text) return
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
      text,
      images: attachedImages.length > 0 ? attachedImages : undefined,
    }
    const nextHistory = [...messages, userMsg]
    autoFollowRef.current = true
    updateSessionMessages(targetSessionId, prev => [...prev, userMsg], text)
    setInput('')
    setPendingImages([])
    setSlashOpen(false)
    setSending(true)
    setStreamingText('')
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
          suggestions: result.suggestions,
        },
      ])
      if (autoSpeak && !result.cancelled && result.reply.trim() && bridge.testVoiceSpeak) {
        void bridge.testVoiceSpeak(result.reply).catch(() => {})
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

  const toggleAutoSpeak = (): void => {
    setAutoSpeak(prev => {
      const next = !prev
      try {
        window.localStorage.setItem('whale.mainChatAutoSpeak.v1', String(next))
      } catch {
        // Ignore storage errors.
      }
      if (!next) void bridge.stopVoiceSpeaking?.().catch(() => {})
      return next
    })
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

  const phaseLabel = streamingPhase.startsWith('tool:')
    ? t('正在调用原生能力…')
    : streamingPhase === 'synthesizing'
      ? t('正在整理回答…')
      : t('正在思考…')

  const renderReceiptIcon = (kind: ActionReceipt['kind']) => {
    if (style === 'cute') {
      if (kind === 'secretary') return <CuteIcon name="notifications" size={18} />
      if (kind === 'knowledge') return <CuteIcon name="memory" size={18} />
      if (kind === 'memory') return <CuteIcon name="assistant" size={18} />
      return <CuteIcon name="workshop" size={18} />
    }
    if (kind === 'secretary') return <BellRing size={14} aria-hidden="true" />
    if (kind === 'knowledge') return <BookmarkCheck size={14} aria-hidden="true" />
    if (kind === 'memory') return <Brain size={14} aria-hidden="true" />
    return <Hammer size={14} aria-hidden="true" />
  }

  return (
    <section className="whale-chat-screen" aria-label={t('鲸鱼助手对话')}>
      <header className="whale-chat-header is-minimal">
        <div className="whale-chat-shortcuts" aria-label={t('常用功能入口')}>
          <button
            type="button"
            className={`whale-icon-chip${autoSpeak ? ' is-active' : ''}`}
            title={autoSpeak ? t('关闭自动朗读') : t('开启自动朗读')}
            aria-label={t('切换自动朗读')}
            aria-pressed={autoSpeak}
            onClick={toggleAutoSpeak}
          >
            {style === 'cute' ? <CuteIcon name={autoSpeak ? 'voice-on' : 'voice-off'} size={21} /> : (autoSpeak ? <Volume2 size={17} /> : <VolumeX size={17} />)}
          </button>
          <button
            type="button"
            className={`whale-icon-chip${sessionsOpen ? ' is-active' : ''}`}
            title={t('会话列表')}
            aria-label={t('会话列表')}
            aria-expanded={sessionsOpen}
            onClick={() => setSessionsOpen(open => !open)}
          >
            {style === 'cute' ? <CuteIcon name="chat" size={21} /> : <MessagesSquare size={17} />}
          </button>
          <button
            type="button"
            className="whale-icon-chip"
            title={t('新对话')}
            aria-label={t('新对话')}
            disabled={sending}
            onClick={startNewSession}
          >
            {style === 'cute' ? <CuteIcon name="new-chat" size={21} /> : <MessageSquarePlus size={17} />}
          </button>
        </div>
      </header>

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
          const el = event.currentTarget
          autoFollowRef.current = el.scrollHeight - (el.scrollTop + el.clientHeight) <= 72
        }}
      >
        {messages.length === 0 ? (
          <div className="whale-chat-empty">
            <div className="whale-hero-stage">
              <img
                src={heroMark}
                alt=""
                width={style === 'cute' ? 88 : 56}
                height={style === 'cute' ? 88 : 56}
                className="whale-chat-hero-mark"
              />
            </div>
            <span className="whale-cute-greeting">{t('主人，小鲸鱼一直都在哦～')}</span>
            <h2>{t('今天想一起做点什么？')}</h2>
            <div className="whale-quick-hub" aria-label={t('快捷空间')}>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('assistant-memory')}>
                {style === 'cute' ? <CuteIcon name="memory" size={19} /> : <BookOpen size={14} aria-hidden="true" />}
                {t('记忆与资料')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('assistant-tasks')}>
                {style === 'cute' ? <CuteIcon name="tasks" size={19} /> : <ListTodo size={14} aria-hidden="true" />}
                {t('事项与日程')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('tools')}>
                {style === 'cute' ? <CuteIcon name="workshop" size={19} /> : <Hammer size={14} aria-hidden="true" />}
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
                  {style === 'cute' && <CuteIcon name={item.icon} size={28} className="whale-starter-icon" />}
                  <strong>{t(item.label)}</strong>
                  <span>{item.text}</span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map(msg => {
            const receipts = msg.role === 'assistant' && !msg.error ? extractActionReceipts(msg.steps) : []
            return (
              <article key={msg.id} className={`whale-msg whale-msg-${msg.role}${msg.error ? ' is-error' : ''}`}>
                {msg.steps && msg.steps.length > 0 && (
                  <details className="whale-msg-steps">
                    <summary>
                      {style === 'cute' ? <CuteIcon name="workshop" size={16} /> : <Wrench size={13} aria-hidden="true" />}
                      {style === 'cute' ? `×${msg.steps.length}` : t('已调用 {0} 项原生能力', msg.steps.length)}
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
                    <img src={whaleChanAvatar} alt="" width={30} height={30} className="whale-msg-inline-avatar" />
                    <div className="whale-msg-col">
                      <div
                        className="whale-msg-bubble whale-markdown"
                        dangerouslySetInnerHTML={{ __html: renderCachedMarkdown(msg.id, msg.text) }}
                      />
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
                {receipts.length > 0 && (
                  <div className="whale-receipt-list" aria-label={t('操作回执')}>
                    {receipts.map(receipt => (
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
                          <button
                            type="button"
                            className="whale-receipt-btn is-primary"
                            onClick={() => onNavigate(receipt.view)}
                          >
                            {t(receipt.actionLabel)}
                          </button>
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
                    </span>
                    <button
                      type="button"
                      className="whale-copy-btn"
                      aria-label={t('复制回复')}
                      onClick={() => copyReply(msg.id, msg.text)}
                    >
                      {copiedId === msg.id ? <Check size={13} /> : <Copy size={13} />}
                    </button>
                    {bridge.testVoiceSpeak && (
                      <button
                        type="button"
                        className="whale-copy-btn"
                        aria-label={t('朗读回复')}
                        title={t('朗读回复')}
                        onClick={() => { void bridge.testVoiceSpeak?.(msg.text).catch(() => {}) }}
                      >
                        <Volume2 size={13} />
                      </button>
                    )}
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
            {(streamingSteps.length > 0 || style !== 'cute') && (
              <div className="whale-live-plan-bar">
                <div className="whale-live-plan-visual" title={phaseLabel}>
                  <span className="whale-plan-whale-dot" aria-hidden="true">🐳</span>
                  {streamingSteps.length > 0 && (
                    <span className="whale-step-dots" aria-label={t('已执行 {0} 步', streamingSteps.length)}>
                      {streamingSteps.slice(-6).map((st, idx) => (
                        <span key={`dot-${idx}`} className={`whale-step-dot${st.ok ? ' is-ok' : ' is-warn'}`} title={st.label} />
                      ))}
                    </span>
                  )}
                  <span className="whale-typing-dots" aria-hidden="true"><i /><i /><i /></span>
                  {style !== 'cute' && <span className="whale-live-plan-status">{phaseLabel}</span>}
                </div>
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
            {streamingText ? (
              <div
                className="whale-msg-bubble whale-markdown whale-streaming"
                dangerouslySetInnerHTML={{ __html: md.render(stripStreamingMarker(streamingText)) }}
              />
            ) : (
              <div className="whale-msg-bubble whale-thinking">
                {style === 'cute'
                  ? <span className="whale-typing-dots is-large" aria-label={phaseLabel}><i /><i /><i /></span>
                  : phaseLabel}
              </div>
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
              {style === 'cute' ? <CuteIcon name="memory" size={19} /> : <BookmarkCheck size={14} aria-hidden="true" />}
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
          accept="image/*"
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
          {style === 'cute' ? <CuteIcon name="slash" size={20} /> : <Slash size={15} />}
        </button>
        <button
          type="button"
          className="whale-img-btn"
          aria-label={t('添加图片')}
          title={t('添加图片')}
          onClick={() => fileInputRef.current?.click()}
        >
          {style === 'cute' ? <CuteIcon name="appearance" size={20} /> : <ImagePlus size={16} />}
        </button>
        <input
          ref={inputRef}
          type="text"
          value={input}
          placeholder={t('和鲸鱼娘聊聊…')}
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
          {recording ? <Square size={14} /> : (style === 'cute' ? <CuteIcon name="mic" size={21} /> : <Mic size={16} />)}
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
            disabled={input.trim() === '' && pendingImages.length === 0}
            aria-label={t('发送')}
            title={t('发送')}
          >
            {style === 'cute' ? <CuteIcon name="send" size={21} /> : <ArrowUp size={18} />}
          </button>
        )}
      </form></div>
    </section>
  )
}
