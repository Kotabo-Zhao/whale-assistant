import { useEffect, useMemo, useRef, useState } from 'react'
import MarkdownIt from 'markdown-it'
import { ArrowUp, BookOpen, Check, CheckCircle2, Copy, Hammer, ListTodo, RotateCcw, Settings2, Sparkles, Wrench } from 'lucide-react'
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

const STARTER_PROMPTS = [
  { label: '整理今日待办', text: '帮我梳理一下今天最重要的三件事，按优先级排个顺序。' },
  { label: '查我的长期记忆', text: '查一下我之前让你记住的偏好和重要信息。' },
  { label: '做个离线小工具', text: '帮我在功能工坊创建一个极简「每日习惯打卡」离线小工具，支持数据持久化。' },
  { label: '查看后台状态', text: '帮我看看手机电量和当前有没有后台任务在运行。' },
] as const

const STORAGE_KEY = 'whale.nativeChatHistory.v1'

function loadHistory(): WhaleChatMessage[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.slice(-30) as WhaleChatMessage[]
  } catch {
    return []
  }
}

export interface WhaleChatScreenProps {
  bridge: RuntimeBridge
  onNavigate: (view: AppView) => void
}

export function WhaleChatScreen({ bridge, onNavigate }: WhaleChatScreenProps) {
  const style = useInterfaceStyle()
  const headerMark = style === 'simple' ? grokMark : whaleChanBadge
  const heroMark = style === 'simple' ? grokMark : whaleChanAvatar
  const [messages, setMessages] = useState<WhaleChatMessage[]>(loadHistory)
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const md = useMemo(() => new MarkdownIt({ html: false, linkify: true, breaks: true }), [])

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-30)))
    } catch {
      // Ignore storage quota errors.
    }
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  const sendText = async (raw: string): Promise<void> => {
    const text = raw.trim()
    if (!text || sending) return
    const userMsg: WhaleChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      text,
    }
    const nextHistory = [...messages, userMsg]
    setMessages(nextHistory)
    setInput('')
    setSending(true)
    try {
      const historyPayload = nextHistory.slice(-12).map(m => ({ role: m.role, text: m.text }))
      const result = await bridge.whaleNativeChat({
        message: text,
        history: historyPayload.slice(0, -1),
        sessionId: 'whale-main',
      })
      setMessages(prev => [
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
      setMessages(prev => [
        ...prev,
        {
          id: `e-${Date.now()}`,
          role: 'assistant',
          text: reason || t('暂时无法连接模型服务，请检查「我的 → 模型与密钥」配置。'),
          error: true,
        },
      ])
    } finally {
      setSending(false)
    }
  }

  const copyReply = (id: string, text: string): void => {
    void navigator.clipboard?.writeText(text).then(() => {
      setCopiedId(id)
      window.setTimeout(() => setCopiedId(current => (current === id ? null : current)), 1500)
    }).catch(() => {})
  }

  return (
    <section className="whale-chat-screen" aria-label={t('鲸鱼助手对话')}>
      <header className="whale-chat-header">
        <div className="whale-chat-brand">
          <img src={headerMark} alt="" width={30} height={30} className="whale-brand-avatar" />
          <div>
            <h1>{t('鲸鱼助手')}</h1>
            <span className="whale-chat-badge">
              <Sparkles size={12} aria-hidden="true" />
              {t('原生直连 · 极速省电')}
            </span>
          </div>
        </div>
        <div className="whale-chat-shortcuts" aria-label={t('常用功能入口')}>
          {messages.length > 0 && (
            <button
              type="button"
              className="whale-icon-chip"
              title={t('新对话')}
              aria-label={t('新对话')}
              disabled={sending}
              onClick={() => setMessages([])}
            >
              <RotateCcw size={16} />
            </button>
          )}
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
            <div className="whale-msg-bubble whale-thinking">{t('正在思考…')}</div>
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
