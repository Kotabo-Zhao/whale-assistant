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
  ListTodo,
  MessageSquarePlus,
  MessagesSquare,
  Pencil,
  Slash,
  Trash2,
  Wrench,
} from 'lucide-react'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanAvatar from '../assets/whale-chan-avatar.png'
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

interface ActionReceipt {
  key: string
  kind: 'secretary' | 'knowledge' | 'memory' | 'workshop'
  title: string
  view: AppView
  actionLabel: string
  followUpPrompt?: string
  followUpLabel?: string
}

const STARTER_PROMPTS = [
  { label: '整理今日待办', text: '帮我梳理一下今天最重要的三件事，按优先级排个顺序。' },
  { label: '查我的长期记忆', text: '查一下我之前让你记住的偏好和重要信息。' },
  { label: '做个离线小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。' },
  { label: '查看后台状态', text: '帮我看看手机电量和当前有没有后台任务在运行。' },
] as const

const QUICK_SLASH_ACTIONS = [
  { id: 'remind', label: '设提醒', prefix: '提醒我 ', sendNow: false },
  { id: 'save-doc', label: '存资料', prefix: '帮我把下面内容存入本地资料库并提炼要点：\n', sendNow: false },
  { id: 'recall', label: '查记忆', prefix: '查一下我之前让你记住的偏好和近期重要事项。', sendNow: true },
  { id: 'workshop', label: '造小工具', prefix: '帮我在功能工坊创作并立即启用一个离线小工具：', sendNow: false },
] as const

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
  const [sending, setSending] = useState(false)
  const [streamingText, setStreamingText] = useState('')
  const [streamingSteps, setStreamingSteps] = useState<WhaleNativeChatStep[]>([])
  const [streamingPhase, setStreamingPhase] = useState('')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const activeRequestRef = useRef<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const md = useMemo(() => new MarkdownIt({ html: false, linkify: true, breaks: true }), [])

  const currentSession = useMemo(
    () => sessions.find(s => s.id === activeId) ?? sessions[0],
    [sessions, activeId],
  )
  const messages = currentSession.messages
  const showArchiveHint = !sending && looksLikeArchivableContent(input) && !input.startsWith('帮我把下面内容存入')

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

  const renderReceiptIcon = (kind: ActionReceipt['kind']) => {
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
            className={`whale-icon-chip${sessionsOpen ? ' is-active' : ''}`}
            title={t('会话列表')}
            aria-label={t('会话列表')}
            aria-expanded={sessionsOpen}
            onClick={() => setSessionsOpen(open => !open)}
          >
            <MessagesSquare size={17} />
          </button>
          <button
            type="button"
            className="whale-icon-chip"
            title={t('新对话')}
            aria-label={t('新对话')}
            disabled={sending}
            onClick={startNewSession}
          >
            <MessageSquarePlus size={17} />
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
                <BookOpen size={14} aria-hidden="true" />
                {t('记忆与资料')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('assistant-tasks')}>
                <ListTodo size={14} aria-hidden="true" />
                {t('事项与日程')}
              </button>
              <button type="button" className="whale-hub-pill" onClick={() => onNavigate('tools')}>
                <Hammer size={14} aria-hidden="true" />
                {t('功能工坊')}
              </button>
            </div>
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
          messages.map(msg => {
            const receipts = msg.role === 'assistant' && !msg.error ? extractActionReceipts(msg.steps) : []
            return (
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
                  <div className="whale-msg-row">
                    <img src={whaleChanAvatar} alt="" width={30} height={30} className="whale-msg-inline-avatar" />
                    <div className="whale-msg-col">
                      <div
                        className="whale-msg-bubble whale-markdown"
                        dangerouslySetInnerHTML={{ __html: md.render(msg.text) }}
                      />
                    </div>
                  </div>
                ) : (
                  <div className="whale-msg-bubble">
                    {msg.text}
                    {msg.error && (msg.text.includes('API Key') || msg.text.includes('模型与密钥')) && (
                      <div style={{ marginTop: 8 }}>
                        <button
                          type="button"
                          className="whale-receipt-btn is-primary"
                          onClick={() => onNavigate('settings-models')}
                        >
                          {t('前往配置模型与密钥')}
                        </button>
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
                  </footer>
                )}
              </article>
            )
          })
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

      {(showArchiveHint || slashOpen) && (
        <div className="whale-composer-assist" aria-label={t('快捷指令与归档建议')}>
          {showArchiveHint && (
            <button
              type="button"
              className="whale-assist-pill is-accent"
              onClick={() => {
                void sendText(`帮我把下面内容存入本地资料库并提炼核心要点：\n\n${input.trim()}`)
              }}
            >
              <BookmarkCheck size={13} aria-hidden="true" />
              {t('顺手存入资料库并总结')}
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

      <div className="whale-chat-composer-wrap"><form
        className="whale-chat-composer"
        onSubmit={event => {
          event.preventDefault()
          void sendText(input)
        }}
      >
        <button
          type="button"
          className={`whale-slash-btn${slashOpen ? ' is-active' : ''}`}
          aria-label={t('快捷指令')}
          title={t('快捷指令')}
          aria-expanded={slashOpen}
          disabled={sending}
          onClick={() => setSlashOpen(open => !open)}
        >
          <Slash size={15} />
        </button>
        <input
          ref={inputRef}
          type="text"
          value={input}
          disabled={sending}
          placeholder={t('发消息、粘贴链接或输入 / 快捷记录…')}
          aria-label={t('输入消息')}
          onChange={event => {
            const val = event.target.value
            setInput(val)
            if (val === '/') setSlashOpen(true)
            else if (val === '') setSlashOpen(false)
          }}
        />
        <button
          type="submit"
          className="whale-send-btn"
          disabled={sending || input.trim() === ''}
          aria-label={t('发送')}
        >
          <ArrowUp size={18} />
        </button>
      </form></div>
    </section>
  )
}
