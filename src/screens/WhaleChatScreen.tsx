import { useEffect, useMemo, useRef, useState } from 'react'
import MarkdownIt from 'markdown-it'
import {
  ArrowUp,
  BookOpen,
  Check,
  CheckCircle2,
  Copy,
  Hammer,
  ListTodo,
  MessageSquarePlus,
  MessagesSquare,
  Pencil,
  Settings2,
  Sparkles,
  Trash2,
  Wrench,
} from 'lucide-react'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanAvatar from '../assets/whale-chan-avatar.png'
import whaleChanBadge from '../assets/whale-chan-badge.png'
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
}

export interface WhaleChatSession {
  id: string
  title: string
  updatedAt: number
  messages: WhaleChatMessage[]
}

const STARTER_PROMPTS = [
  { label: '整理今日待办', text: '帮我梳理一下今天最重要的三件事，按优先级排个顺序。' },
  { label: '查我的长期记忆', text: '查一下我之前让你记住的偏好和重要信息。' },
  { label: '做个离线小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。' },
  { label: '查看后台状态', text: '帮我看看手机电量和当前有没有后台任务在运行。' },
] as const

const LEGACY_STORAGE_KEY = 'whale.nativeChatHistory.v1'
const SESSIONS_STORAGE_KEY = 'whale.nativeChatSessions.v2'
const ACTIVE_SESSION_KEY = 'whale.nativeChatActiveSession.v2'
const MAX_SESSIONS = 24
const MAX_MESSAGES_PER_SESSION = 40

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
  const headerMark = style === 'simple' ? grokMark : whaleChanBadge
  const heroMark = style === 'simple' ? grokMark : whaleChanAvatar
  const [sessions, setSessions] = useState<WhaleChatSession[]>(loadSessions)
  const [activeId, setActiveId] = useState<string>(() => loadActiveSessionId(loadSessions()))
  const [sessionsOpen, setSessionsOpen] = useState(false)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [streamingText, setStreamingText] = useState('')
  const [streamingSteps, setStreamingSteps] = useState<WhaleNativeChatStep[]>([])
  const [streamingPhase, setStreamingPhase] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const activeRequestRef = useRef<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const md = useMemo(() => new MarkdownIt({ html: false, linkify: true, breaks: true }), [])

  const currentSession = useMemo(
    () => sessions.find(s => s.id === activeId) ?? sessions[0],
    [sessions, activeId],
  )
  const messages = currentSession.messages

  useEffect(() => {
    try {
      window.localStorage.setItem(SESSIONS_STORAGE_KEY, JSON.stringify(sessions.slice(0, MAX_SESSIONS)))
      window.localStorage.setItem(ACTIVE_SESSION_KEY, currentSession.id)
    } catch {
      // Ignore storage quota errors.
    }
  }, [sessions, currentSession.id])

  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
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

  const sendText = async (raw: string): Promise<void> => {
    const text = raw.trim()
    if (!text || sending) return
    const targetSessionId = currentSession.id
    const requestId = `req-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`
    const userMsg: WhaleChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      text,
    }
    const nextHistory = [...messages, userMsg]
    updateSessionMessages(targetSessionId, prev => [...prev, userMsg], text)
    setInput('')
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
        },
      ])
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
    }
  }

  const copyReply = (id: string, text: string): void => {
    void navigator.clipboard?.writeText(text).then(() => {
      setCopiedId(id)
      window.setTimeout(() => setCopiedId(current => (current === id ? null : current)), 1500)
    }).catch(() => {})
  }

  const phaseLabel = streamingPhase.startsWith('tool:')
    ? t('正在调用原生能力…')
    : streamingPhase === 'synthesizing'
      ? t('正在整理回答…')
      : t('正在思考…')

  return (
    <section className="whale-chat-screen" aria-label={t('鲸鱼助手对话')}>
      <header className="whale-chat-header">
        <div className="whale-chat-brand">
          <img src={headerMark} alt="" width={30} height={30} className="whale-brand-avatar" />
          <div>
            <h1>{currentSession.title === '新对话' ? t('鲸鱼助手') : currentSession.title}</h1>
            <span className="whale-chat-badge">
              <Sparkles size={12} aria-hidden="true" />
              {t('原生直连 · 流式加速')}
            </span>
          </div>
        </div>
        <div className="whale-chat-shortcuts" aria-label={t('常用功能入口')}>
          <button
            type="button"
            className={`whale-icon-chip${sessionsOpen ? ' is-active' : ''}`}
            title={t('会话列表')}
            aria-label={t('会话列表')}
            aria-expanded={sessionsOpen}
            onClick={() => setSessionsOpen(open => !open)}
          >
            <MessagesSquare size={16} />
          </button>
          <button
            type="button"
            className="whale-icon-chip"
            title={t('新对话')}
            aria-label={t('新对话')}
            disabled={sending}
            onClick={startNewSession}
          >
            <MessageSquarePlus size={16} />
          </button>
          <button type="button" className="whale-icon-chip" title={t('记忆与资料')} aria-label={t('记忆与资料')} onClick={() => onNavigate('assistant-memory')}>
            <BookOpen size={17} />
          </button>
          <button type="button" className="whale-icon-chip" title={t('事项与日程')} aria-label={t('事项与日程')} onClick={() => onNavigate('assistant-tasks')}>
            <ListTodo size={17} />
          </button>
          <button type="button" className="whale-icon-chip" title={t('功能工坊')} aria-label={t('功能工坊')} onClick={() => onNavigate('tools')}>
            <Hammer size={17} />
          </button>
          <button type="button" className="whale-icon-chip" title={t('设置')} aria-label={t('设置')} onClick={() => onNavigate('settings')}>
            <Settings2 size={17} />
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

      <div className="whale-chat-stream" ref={listRef}>
        {messages.length === 0 ? (
          <div className="whale-chat-empty">
            <img
              src={heroMark}
              alt=""
              width={style === 'cute' ? 76 : 60}
              height={style === 'cute' ? 76 : 60}
              className="whale-chat-hero-mark"
            />
            <h2>{t('今天想一起做点什么？')}</h2>
            <p>{t('已脱离 DeepSeek Harness 独立运行，随时可以聊天、查记忆、管事项或一句话造个小工具。')}</p>
            <div className="whale-starter-grid">
              {STARTER_PROMPTS.map(item => (
                <button
                  key={item.label}
                  type="button"
                  className="whale-starter-card"
                  disabled={sending}
                  onClick={() => { void sendText(item.text) }}
                >
                  <strong>{t(item.label)}</strong>
                  <span>{item.text}</span>
                </button>
              ))}
            </div>
          </div>
        ) : (
          messages.map(msg => (
            <article key={msg.id} className={`whale-msg whale-msg-${msg.role}${msg.error ? ' is-error' : ''}`}>
              {msg.steps && msg.steps.length > 0 && (
                <details className="whale-msg-steps">
                  <summary>
                    <Wrench size={13} aria-hidden="true" />
                    {t('已调用 {0} 项原生能力', msg.steps.length)}
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
                <div
                  className="whale-msg-bubble whale-markdown"
                  dangerouslySetInnerHTML={{ __html: md.render(msg.text) }}
                />
              ) : (
                <div className="whale-msg-bubble">{msg.text}</div>
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
                </footer>
              )}
            </article>
          ))
        )}
        {sending && (
          <div className="whale-msg whale-msg-assistant" role="status">
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
                dangerouslySetInnerHTML={{ __html: md.render(streamingText) }}
              />
            ) : (
              <div className="whale-msg-bubble whale-thinking">{phaseLabel}</div>
            )}
          </div>
        )}
      </div>

      <form
        className="whale-chat-composer"
        onSubmit={event => {
          event.preventDefault()
          void sendText(input)
        }}
      >
        <input
          type="text"
          value={input}
          disabled={sending}
          placeholder={t('发消息给鲸鱼助手…')}
          aria-label={t('输入消息')}
          onChange={event => setInput(event.target.value)}
        />
        <button
          type="submit"
          className="whale-send-btn"
          disabled={sending || input.trim() === ''}
          aria-label={t('发送')}
        >
          <ArrowUp size={18} />
        </button>
      </form>
    </section>
  )
}
