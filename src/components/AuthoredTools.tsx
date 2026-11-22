import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowUpRight,
  Check,
  ChevronDown,
  ChevronRight,
  Eye,
  MoreHorizontal,
  Pin,
  Plus,
  Power,
  RotateCcw,
  Search,
  Smartphone,
  Sparkles,
  Trash2,
  Wand2,
  Wrench,
  X,
} from 'lucide-react'
import type { RuntimeBridge } from '../platform/types'
import './AuthoredTools.css'

type Tool = {
  id: string
  title: string
  revision: string
  latest?: string
  previous?: string
  pinned?: boolean
  hasUpdate?: boolean
  activatedAt?: number
  isDraft?: boolean
}

type FilterTab = 'all' | 'active' | 'draft' | 'pinned'

const isTool = (item: unknown): item is Tool => {
  if (!item || typeof item !== 'object') return false
  const value = item as Partial<Tool>
  return typeof value.id === 'string' && /^[a-z][a-z0-9-]{0,47}$/.test(value.id) &&
    typeof value.title === 'string' && typeof value.revision === 'string' && /^[a-f0-9]{64}$/.test(value.revision)
}

const WORKSHOP_TEMPLATES = [
  {
    id: 'habit',
    label: '习惯打卡',
    draft: '请为鲸鱼助手创建一个离线「每日习惯打卡」小工具，支持自定义习惯项、一键打卡、连续天数统计和简洁周视图。',
  },
  {
    id: 'pomodoro',
    label: '番茄专注',
    draft: '请为鲸鱼助手创建一个离线「番茄专注计时器」，支持 25/5 分钟循环、任务标签、完成统计和极简深浅色界面。',
  },
  {
    id: 'ledger',
    label: '随手记账',
    draft: '请为鲸鱼助手创建一个离线「随手记账本」，支持快速记一笔、分类筛选、本月支出汇总和导出摘要。',
  },
  {
    id: 'checklist',
    label: '灵感清单',
    draft: '请为鲸鱼助手创建一个离线「极简待办与灵感清单」，支持置顶重要事项、一键归档和快速搜索。',
  },
  {
    id: 'countdown',
    label: '倒数日',
    draft: '请为鲸鱼助手创建一个离线「重要日期倒数日」小工具，支持添加纪念日与考试倒计时、分类色标和天数高亮。',
  },
  {
    id: 'converter',
    label: '快捷换算',
    draft: '请为鲸鱼助手创建一个离线「常用单位与汇率换算器」，支持长度、重量、温度与常见数值一键互转。',
  },
] as const

const MONOGRAM_TONES = ['tone-a', 'tone-b', 'tone-c', 'tone-d', 'tone-e', 'tone-f'] as const

function toolTone(id: string): string {
  let hash = 0
  for (let i = 0; i < id.length; i += 1) {
    hash = ((hash << 5) - hash + id.charCodeAt(i)) | 0
  }
  return MONOGRAM_TONES[Math.abs(hash) % MONOGRAM_TONES.length]
}

function toolGlyph(title: string): string {
  const clean = title.trim()
  if (!clean) return '工'
  return Array.from(clean)[0] ?? '工'
}

export function AuthoredTools({ bridge, create = false }: { bridge: RuntimeBridge; create?: boolean }) {
  const [tools, setTools] = useState<Tool[]>([])
  const [drafts, setDrafts] = useState<Tool[]>([])
  const [expanded, setExpanded] = useState(false)
  const [managedId, setManagedId] = useState<string | null>(null)
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null)
  const [filter, setFilter] = useState<FilterTab>('all')
  const [message, setMessage] = useState('')
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [retry, setRetry] = useState(0)
  const [query, setQuery] = useState('')
  const [opening, setOpening] = useState<string | null>(null)
  const action = useRef(false)
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

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
        const rawDrafts = Array.isArray(result.drafts) ? result.drafts : []
        const validDrafts = rawDrafts.filter(isTool).map(item => ({ ...item, isDraft: true }))
        if (live && request === version) {
          setTools(result.items.map(item => ({ ...item, isDraft: false })))
          setDrafts(validDrafts)
          setLoadState('ready')
        }
      } catch {
        if (live && request === version) setLoadState('failed')
      }
    }
    const onChange = () => { void refresh() }
    const listener = bridge.addFeatureToolsListener?.(onChange).catch(() => null)
    document.addEventListener('visibilitychange', onChange)
    void refresh()
    return () => {
      live = false
      document.removeEventListener('visibilitychange', onChange)
      void listener?.then(handle => handle?.remove()).catch(() => {})
    }
  }, [bridge, retry])

  const open = async (target: Tool | 'create' | 'library' | { templateId: string; draft: string }) => {
    if (action.current) return
    action.current = true
    const key = typeof target === 'string' ? target : 'templateId' in target ? `tpl:${target.templateId}` : target.id
    setOpening(key)
    setMessage('')
    try {
      if (target === 'create') {
        await bridge.insertTaskDraft('请为鲸鱼助手创建一个新功能。我希望它能：')
      } else if (typeof target === 'object' && 'draft' in target) {
        await bridge.insertTaskDraft(target.draft)
      } else if (target === 'library') {
        await bridge.companionDashboard({ operation: 'featureLibrary' })
      } else if (target.isDraft) {
        await bridge.companionDashboard({ operation: 'featureOpen', id: target.id, revision: target.revision, preview: true })
      } else {
        await bridge.companionDashboard({ operation: 'featureOpen', id: target.id, revision: target.revision })
      }
    } catch {
      if (mounted.current) {
        setMessage(target === 'create' || (typeof target === 'object' && 'draft' in target) ? '无法进入创作对话，请重试。' : '工具未能打开，请重试。')
      }
    } finally {
      action.current = false
      if (mounted.current) setOpening(null)
    }
  }

  const runManageAction = async (
    key: string,
    payload: Record<string, unknown>,
    successMsg: string,
    reloadAfter = true,
  ) => {
    if (action.current) return
    action.current = true
    setOpening(key)
    setMessage('')
    try {
      await bridge.companionDashboard(payload)
      if (mounted.current) {
        setMessage(successMsg)
        setConfirmDeleteId(null)
        if (reloadAfter) setRetry(v => v + 1)
      }
    } catch {
      if (mounted.current) setMessage('操作未能完成，请刷新后重试。')
    } finally {
      action.current = false
      if (mounted.current) setOpening(null)
    }
  }

  const allItems = useMemo(() => {
    if (!create) return tools
    return [...tools, ...drafts]
  }, [create, tools, drafts])

  const pinnedCount = useMemo(() => tools.filter(item => item.pinned).length, [tools])

  const filtered = useMemo(() => {
    const q = query.trim().toLocaleLowerCase()
    return allItems.filter(item => {
      if (create) {
        if (filter === 'active' && item.isDraft) return false
        if (filter === 'draft' && !item.isDraft && !item.hasUpdate) return false
        if (filter === 'pinned' && !item.pinned) return false
      }
      if (!q) return true
      return item.title.toLocaleLowerCase().includes(q) || item.id.toLocaleLowerCase().includes(q)
    })
  }, [allItems, create, filter, query])

  const visible = create || expanded ? filtered : filtered.slice(0, 4)

  return (
    <section className={'authored-tools' + (create ? ' authored-tools-full' : '')} aria-label="我的工具">
      {create && (
        <div className="workshop-studio-hero">
          <div className="workshop-hero-copy">
            <div className="workshop-hero-title-row">
              <h2>功能工坊</h2>
              <div className="workshop-stat-strip" aria-label="工坊统计">
                <span className="workshop-stat-pill is-active">已启用 {tools.length}</span>
                {drafts.length > 0 && <span className="workshop-stat-pill is-draft">待启用 {drafts.length}</span>}
                {pinnedCount > 0 && <span className="workshop-stat-pill">置顶 {pinnedCount}</span>}
              </div>
            </div>
            <p>用一句话让鲸鱼为你打造专属离线小工具，随时预览、置顶到桌宠、加到桌面或直接对话迭代。</p>
            <div className="tool-template-strip" aria-label="快捷创作灵感">
              {WORKSHOP_TEMPLATES.map(tpl => (
                <button
                  key={tpl.id}
                  type="button"
                  className="tool-template-chip"
                  disabled={opening !== null}
                  onClick={() => { void open({ templateId: tpl.id, draft: tpl.draft }) }}
                >
                  <Sparkles size={12} aria-hidden="true" />
                  {tpl.label}
                </button>
              ))}
            </div>
          </div>
          <div className="tool-creation-actions">
            <button type="button" className="button button-primary" disabled={opening !== null} onClick={() => { void open('create') }}>
              <Plus size={18} />
              {opening === 'create' ? '正在打开…' : '去对话创作'}
            </button>
            <button type="button" className="button button-secondary" disabled={opening !== null} onClick={() => { void open('library') }}>
              功能工坊
              <ChevronRight size={16} />
            </button>
          </div>
        </div>
      )}

      <div className="authored-tools-heading">
        <h3>
          <Wrench size={17} aria-hidden="true" />
          我的工具
          {loadState === 'ready' && <span className="tool-count">{allItems.length}</span>}
        </h3>
        {!create && (
          <button type="button" disabled={opening !== null} onClick={() => { void open('library') }}>
            功能工坊 <ChevronRight size={15} aria-hidden="true" />
          </button>
        )}
      </div>

      {create && allItems.length > 0 && (
        <div className="workshop-toolbar">
          <div className="workshop-filter-tabs" role="tablist" aria-label="筛选工具状态">
            {((() => {
              const tabs: Array<[FilterTab, string]> = [
                ['all', `全部 ${allItems.length}`],
                ['active', `已启用 ${tools.length}`],
              ]
              const draftTotal = drafts.length + tools.filter(t => t.hasUpdate).length
              if (draftTotal > 0) tabs.push(['draft', `待启用 ${draftTotal}`])
              if (pinnedCount > 0) tabs.push(['pinned', `置顶 ${pinnedCount}`])
              return tabs
            })()).map(([tabKey, tabLabel]) => (
              <button
                key={tabKey}
                type="button"
                role="tab"
                aria-selected={filter === tabKey}
                className={`workshop-filter-pill${filter === tabKey ? ' is-selected' : ''}`}
                onClick={() => setFilter(tabKey)}
              >
                {tabLabel}
              </button>
            ))}
          </div>
          {allItems.length > 3 && (
            <label className="tool-search">
              <Search size={16} aria-hidden="true" />
              <input
                type="search"
                aria-label="查找我的工具"
                placeholder="搜索工具名称…"
                value={query}
                onChange={event => setQuery(event.target.value)}
              />
              {query && (
                <button type="button" className="tool-search-clear" aria-label="清空搜索" onClick={() => setQuery('')}>
                  <X size={14} />
                </button>
              )}
            </label>
          )}
        </div>
      )}

      {loadState === 'loading' && <p role="status">正在读取工具…</p>}
      {loadState === 'failed' && (
        <div className="tool-list-error" role="status">
          <p>工具列表暂时不可用。{tools.length > 0 ? '下方保留上次结果。' : ''}</p>
          <button className="button button-secondary" type="button" onClick={() => setRetry(value => value + 1)}>
            重新读取工具
          </button>
        </div>
      )}

      {allItems.length > 0 ? (
        <div className={create ? 'workshop-card-grid' : 'authored-tool-links'}>
          {visible.map(tool => {
            if (!create) {
              return (
                <button
                  type="button"
                  className="authored-tool-card"
                  key={tool.id}
                  disabled={opening !== null}
                  onClick={() => { void open(tool) }}
                >
                  <span>{tool.title}</span>
                  {opening === tool.id && <small role="status">正在打开…</small>}
                </button>
              )
            }
            const isManaged = managedId === tool.id
            const isConfirmingDelete = confirmDeleteId === tool.id
            const statusText = tool.isDraft ? '待启用草稿' : tool.hasUpdate ? '有新草稿' : tool.pinned ? '已置顶' : '已启用'
            return (
              <article
                key={tool.id}
                className={`workshop-tool-card${tool.isDraft ? ' is-draft' : ''}${tool.pinned ? ' is-pinned' : ''}${isManaged ? ' is-managed' : ''}`}
              >
                <div className="workshop-tool-main">
                  <button
                    type="button"
                    className="workshop-tool-launch"
                    aria-label={tool.title}
                    disabled={opening !== null}
                    onClick={() => { void open(tool) }}
                  >
                    <span className={`workshop-tool-glyph ${tool.isDraft ? 'tone-draft' : toolTone(tool.id)}`} aria-hidden="true">
                      {toolGlyph(tool.title)}
                    </span>
                    <span className="workshop-tool-meta">
                      <span className="workshop-tool-title">{tool.title}</span>
                      <span className="workshop-tool-sub">
                        <span className={`workshop-status-dot${tool.isDraft ? ' is-draft' : tool.hasUpdate ? ' is-update' : ''}`} />
                        {opening === tool.id ? '正在打开…' : statusText}
                      </span>
                    </span>
                    <ArrowUpRight size={17} className="workshop-launch-arrow" aria-hidden="true" />
                  </button>
                  <div className="workshop-tool-quick">
                    {!tool.isDraft && (
                      <button
                        type="button"
                        className={`workshop-icon-btn${tool.pinned ? ' is-active' : ''}`}
                        title={tool.pinned ? '取消置顶' : '置顶到桌宠'}
                        aria-label={tool.pinned ? `取消置顶 ${tool.title}` : `置顶 ${tool.title}`}
                        disabled={opening !== null}
                        onClick={() => {
                          void runManageAction(
                            `pin:${tool.id}`,
                            { operation: 'featurePin', id: tool.id, pinned: !tool.pinned },
                            tool.pinned ? `已取消置顶「${tool.title}」` : `已置顶「${tool.title}」到桌宠`,
                          )
                        }}
                      >
                        <Pin size={15} />
                      </button>
                    )}
                    <button
                      type="button"
                      className={`workshop-icon-btn${isManaged ? ' is-active' : ''}`}
                      title="管理工具"
                      aria-label={`管理 ${tool.title}`}
                      aria-expanded={isManaged}
                      onClick={() => {
                        setManagedId(prev => (prev === tool.id ? null : tool.id))
                        setConfirmDeleteId(null)
                      }}
                    >
                      {isManaged ? <ChevronDown size={16} /> : <MoreHorizontal size={16} />}
                    </button>
                  </div>
                </div>

                {isManaged && (
                  <div className="workshop-tool-drawer">
                    {tool.isDraft && (
                      <>
                        <button
                          type="button"
                          className="workshop-drawer-btn is-primary"
                          disabled={opening !== null}
                          onClick={() => {
                            void runManageAction(
                              `activate:${tool.id}`,
                              { operation: 'featureActivate', id: tool.id, target: tool.revision, expectedActive: '' },
                              `已启用「${tool.title}」`,
                            )
                          }}
                        >
                          <Check size={14} /> 启用到工坊
                        </button>
                        <button
                          type="button"
                          className="workshop-drawer-btn"
                          disabled={opening !== null}
                          onClick={() => { void open(tool) }}
                        >
                          <Eye size={14} /> 预览草稿
                        </button>
                      </>
                    )}

                    {!tool.isDraft && tool.hasUpdate && tool.latest && (
                      <>
                        <button
                          type="button"
                          className="workshop-drawer-btn is-primary"
                          disabled={opening !== null}
                          onClick={() => {
                            void runManageAction(
                              `publish:${tool.id}`,
                              { operation: 'featureActivate', id: tool.id, target: tool.latest, expectedActive: tool.revision },
                              `已将「${tool.title}」升级至新版本`,
                            )
                          }}
                        >
                          <Check size={14} /> 启用新版本
                        </button>
                        <button
                          type="button"
                          className="workshop-drawer-btn"
                          disabled={opening !== null}
                          onClick={() => {
                            void runManageAction(
                              `preview:${tool.id}`,
                              { operation: 'featureOpen', id: tool.id, revision: tool.latest, preview: true },
                              '',
                              false,
                            )
                          }}
                        >
                          <Eye size={14} /> 预览新草稿
                        </button>
                      </>
                    )}

                    {!tool.isDraft && (
                      <button
                        type="button"
                        className="workshop-drawer-btn"
                        disabled={opening !== null}
                        onClick={() => {
                          void runManageAction(
                            `home:${tool.id}`,
                            { operation: 'featurePinHome', id: tool.id, title: tool.title, revision: tool.revision },
                            `已请求将「${tool.title}」添加到手机桌面`,
                            false,
                          )
                        }}
                      >
                        <Smartphone size={14} /> 加到桌面
                      </button>
                    )}

                    <button
                      type="button"
                      className="workshop-drawer-btn"
                      disabled={opening !== null}
                      onClick={() => {
                        void open({
                          templateId: `refine:${tool.id}`,
                          draft: `请帮我优化功能工坊里的「${tool.title}」（id: ${tool.id}），我希望改进：`,
                        })
                      }}
                    >
                      <Wand2 size={14} /> 对话优化
                    </button>

                    {!tool.isDraft && tool.previous && tool.previous !== tool.revision && (
                      <button
                        type="button"
                        className="workshop-drawer-btn"
                        disabled={opening !== null}
                        onClick={() => {
                          void runManageAction(
                            `rollback:${tool.id}`,
                            { operation: 'featureActivate', id: tool.id, target: tool.previous, expectedActive: tool.revision },
                            `已将「${tool.title}」恢复至上一版本`,
                          )
                        }}
                      >
                        <RotateCcw size={14} /> 恢复上一版
                      </button>
                    )}

                    {!tool.isDraft && (
                      <button
                        type="button"
                        className="workshop-drawer-btn"
                        disabled={opening !== null}
                        onClick={() => {
                          void runManageAction(
                            `disable:${tool.id}`,
                            { operation: 'featureActivate', id: tool.id, target: '', expectedActive: tool.revision },
                            `已停用「${tool.title}」`,
                          )
                        }}
                      >
                        <Power size={14} /> 停用
                      </button>
                    )}

                    {isConfirmingDelete ? (
                      <div className="workshop-delete-confirm">
                        <button
                          type="button"
                          className="workshop-drawer-btn is-danger"
                          disabled={opening !== null}
                          onClick={() => {
                            void runManageAction(
                              `delete:${tool.id}`,
                              { operation: 'featureDelete', id: tool.id },
                              `已删除「${tool.title}」`,
                            )
                          }}
                        >
                          确认删除
                        </button>
                        <button
                          type="button"
                          className="workshop-drawer-btn"
                          onClick={() => setConfirmDeleteId(null)}
                        >
                          取消
                        </button>
                      </div>
                    ) : (
                      <button
                        type="button"
                        className="workshop-drawer-btn is-danger-ghost"
                        disabled={opening !== null}
                        onClick={() => setConfirmDeleteId(tool.id)}
                      >
                        <Trash2 size={14} /> 删除
                      </button>
                    )}
                  </div>
                )}
              </article>
            )
          })}
          {!create && filtered.length > 4 && (
            <button type="button" className="authored-tool-more" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
              {expanded ? '收起' : `显示其余 ${filtered.length - 4} 项`}
            </button>
          )}
          {(query || filter !== 'all') && filtered.length === 0 && (
            <p className="workshop-empty-filter">当前筛选条件下没有匹配的工具。</p>
          )}
        </div>
      ) : (
        loadState === 'ready' && (
          <div className="tool-empty">
            <Wrench size={25} aria-hidden="true" />
            <p>还没有启用的工具。<br />点击上方灵感胶囊或进入功能工坊启用后，会出现在这里。</p>
          </div>
        )
      )}

      {message && <p className="tool-action-message" role="status">{message}</p>}
    </section>
  )
}
