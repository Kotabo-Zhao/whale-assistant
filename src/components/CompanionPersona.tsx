import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { readPngCard } from './sillyTavernCatalog'
import './CompanionPersona.css'

type Card = {
  id: string
  name: string
  description: string
  personality: string
  scenario: string
  examples: string
  greeting: string
  systemPrompt: string
  postHistory: string
  worldBook: string
  lastRefineReason?: string
  lastRefinedAt?: number
}

const blank = (): Card => ({
  id: '',
  name: '',
  description: '',
  personality: '',
  scenario: '',
  examples: '',
  greeting: '',
  systemPrompt: '',
  postHistory: '',
  worldBook: '',
})

const QUICK_TRAITS = [
  '元气软萌、爱用轻快语气词',
  '温柔贴心、会主动记住小细节',
  '略带傲娇但嘴硬心软',
  '理性干练、汇报直接给结论',
  '幽默吐槽、像损友一样轻松',
  '回复简短口语化、不说教',
]

const fields: { key: keyof Omit<Card, 'id' | 'lastRefineReason' | 'lastRefinedAt'>; label: string; hint: string }[] = [
  { key: 'name', label: '角色名', hint: '例如：鲸鱼娘' },
  { key: 'description', label: '角色简介', hint: '身份、背景与重要设定' },
  { key: 'personality', label: '性格与说话方式', hint: '性格特点、语气、称呼与口癖习惯' },
  { key: 'scenario', label: '情境', hint: '角色与用户的关系、日常相处场景' },
  { key: 'examples', label: '示例对话', hint: '写几句示例，帮助角色保持说话风格' },
  { key: 'greeting', label: '开场白', hint: '开启空白桌宠对话时显示' },
  { key: 'systemPrompt', label: '角色附加指引', hint: '角色卡中的 system_prompt，作为桌宠角色设定使用' },
  { key: 'postHistory', label: '对话后指引', hint: '角色卡中的 post_history_instructions（每轮末尾强化语气）' },
  { key: 'worldBook', label: '角色世界书 JSON', hint: '保存 character_book，按关键词匹配当前对话' },
]

export function CompanionPersona({ onBack }: { onBack: () => void }) {
  const [cards, setCards] = useState<Card[]>([])
  const [activeId, setActiveId] = useState('')
  const [draft, setDraft] = useState<Card>(blank)
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [json, setJson] = useState('')
  const [loading, setLoading] = useState(true)
  const [tab, setTab] = useState<'cards' | 'transfer'>('cards')
  const [wbKeys, setWbKeys] = useState('')
  const [wbContent, setWbContent] = useState('')

  const load = async (selected?: string) => {
    const result = await runtimeBridge.companionDashboard({ operation: 'personaList' })
    if (!Array.isArray(result.cards) || typeof result.activeId !== 'string') throw new Error('角色卡格式无效')
    const next = (result.cards as Card[]).map(card => ({ ...blank(), ...card }))
    setCards(next)
    setActiveId(result.activeId)
    setDraft(next.find(card => card.id === (selected ?? result.activeId)) ?? next[0] ?? blank())
  }

  useEffect(() => {
    void load()
      .catch(() => setMessage('无法读取角色卡，请稍后重试。'))
      .finally(() => setLoading(false))
  }, [])

  const action = async (fn: () => Promise<void>, ok: string, failed = '操作未完成，请检查内容后重试。') => {
    setBusy(true)
    setMessage('')
    try {
      await fn()
      setMessage(ok)
    } catch {
      setMessage(failed)
    } finally {
      setBusy(false)
    }
  }

  const save = () => void action(async () => {
    const result = await runtimeBridge.companionDashboard({ operation: 'personaSave', card: draft })
    const saved = result.card as Card | undefined
    if (result.ok !== true || !saved?.id) throw new Error('save failed')
    await load(saved.id)
  }, '角色卡已保存。')

  const appendTrait = (trait: string) => {
    setDraft(prev => {
      const cur = prev.personality.trim()
      if (cur.includes(trait)) return prev
      return { ...prev, personality: cur ? `${cur}\n${trait}` : trait }
    })
  }

  const addWorldBookEntry = () => {
    const keys = wbKeys.split(/[,，、\s]+/).map(s => s.trim()).filter(Boolean)
    const content = wbContent.trim()
    if (keys.length === 0 || !content) {
      setMessage('请填写触发关键词和设定内容。')
      return
    }
    try {
      const book = draft.worldBook.trim()
        ? JSON.parse(draft.worldBook) as { entries?: unknown[] }
        : { entries: [] }
      const entries = Array.isArray(book.entries) ? [...book.entries] : []
      entries.push({ keys, content, enabled: true })
      setDraft(prev => ({ ...prev, worldBook: JSON.stringify({ ...book, entries }, null, 2) }))
      setWbKeys('')
      setWbContent('')
      setMessage('已添加一条世界书设定，别忘了点击保存。')
    } catch {
      setMessage('当前世界书 JSON 格式有误，请先修正。')
    }
  }

  const duplicateDraft = () => {
    setDraft(prev => ({
      ...prev,
      id: '',
      name: prev.name ? `${prev.name} · 副本` : '新角色',
      lastRefineReason: undefined,
      lastRefinedAt: undefined,
    }))
    setMessage('已复制为新草稿，修改后点击保存即可创建。')
  }

  const importCard = (root: Record<string, unknown>) => {
    const data = typeof root.data === 'object' && root.data !== null ? root.data as Record<string, unknown> : root
    const str = (key: string) => typeof data[key] === 'string' ? data[key] : ''
    const book = data.character_book
    const card: Card = {
      id: '',
      name: str('name'),
      description: str('description'),
      personality: str('personality'),
      scenario: str('scenario'),
      examples: str('mes_example') || str('examples'),
      greeting: str('first_mes') || str('greeting'),
      systemPrompt: str('system_prompt') || str('systemPrompt'),
      postHistory: str('post_history_instructions') || str('postHistory'),
      worldBook: book && typeof book === 'object' ? JSON.stringify(book) : str('worldBook'),
    }
    if (!card.name.trim()) throw new Error('missing name')
    setDraft(card)
    setTab('cards')
    setMessage('已导入草稿，请检查并保存。')
  }

  const importJson = () => {
    try {
      importCard(JSON.parse(json) as Record<string, unknown>)
    } catch {
      setMessage('角色卡 JSON 无效或缺少角色名。')
    }
  }

  const chooseFile = async (file: File | undefined) => {
    if (!file) return
    try {
      if (file.size > 16 * 1024 * 1024) throw new Error('too large')
      const bytes = new Uint8Array(await file.arrayBuffer())
      const root = file.name.toLowerCase().endsWith('.png')
        ? readPngCard(bytes)
        : JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>
      importCard(root)
    } catch {
      setMessage('无法读取角色卡，请选择有效的 PNG 或 JSON 文件。')
    }
  }

  const exportJson = () => {
    try {
      const book: unknown = draft.worldBook.trim() ? JSON.parse(draft.worldBook) : undefined
      setJson(JSON.stringify({
        spec: 'chara_card_v2',
        spec_version: '2.0',
        data: {
          name: draft.name,
          description: draft.description,
          personality: draft.personality,
          scenario: draft.scenario,
          first_mes: draft.greeting,
          mes_example: draft.examples,
          system_prompt: draft.systemPrompt,
          post_history_instructions: draft.postHistory,
          ...(book !== undefined ? { character_book: book } : {}),
        },
      }, null, 2))
    } catch {
      setMessage('世界书 JSON 无效，请修正后导出。')
      return
    }
    setMessage('JSON 已生成，可复制保存。')
  }

  return (
    <section className="persona-page" aria-labelledby="persona-title">
      <header className="persona-hero page-heading">
        <div>
          <h2 id="persona-title">鲸鱼娘人格</h2>
          <p>仅支持亲自编辑或让桌宠根据日常对话自主微调修正，角色设定与长期记忆独立保存。</p>
        </div>
        <button type="button" onClick={onBack}>返回</button>
      </header>

      <nav className="persona-tabs" aria-label="角色管理">
        <button type="button" aria-pressed={tab === 'cards'} onClick={() => setTab('cards')}>我的角色</button>
        <button type="button" aria-pressed={tab === 'transfer'} onClick={() => setTab('transfer')}>导入导出</button>
      </nav>

      {message && <p role="status" className="persona-notice">{message}</p>}

      {loading ? <p>正在读取角色卡…</p> : (
        <>
          {tab === 'cards' && (
            <>
              <div className="persona-section-heading">
                <div>
                  <h3>我的角色</h3>
                  <p>当前启用角色会在每次对话生效；聊天中约定新称呼、口癖或语气时，鲸鱼娘也会自动增量修正。</p>
                </div>
                <button type="button" onClick={() => setDraft(blank())}>＋ 新建</button>
              </div>

              <div className="persona-cards">
                {cards.map(card => (
                  <button
                    type="button"
                    key={card.id}
                    className={draft.id === card.id ? 'is-selected' : ''}
                    onClick={() => setDraft({ ...card })}
                  >
                    <span className="persona-avatar">{card.name.slice(0, 1)}</span>
                    <strong>{card.name}</strong>
                    <small>{activeId === card.id ? '● 使用中' : '选择编辑'}</small>
                  </button>
                ))}
              </div>

              {draft.lastRefineReason && (
                <p className="persona-notice" role="note">
                  ✨ 最近一次对话自主修正：{draft.lastRefineReason}
                </p>
              )}

              <fieldset disabled={busy} className="persona-editor">
                <legend>{draft.id ? '编辑角色卡' : '新建角色卡'}</legend>

                <div className="persona-trait-bar" aria-label="一键叠加性格标签">
                  {QUICK_TRAITS.map(trait => (
                    <button key={trait} type="button" className="persona-trait-chip" onClick={() => appendTrait(trait)}>
                      ＋ {trait}
                    </button>
                  ))}
                </div>

                {fields.map(field => (
                  <label key={field.key}>
                    {field.label}
                    {field.key === 'name' ? (
                      <input
                        value={draft.name}
                        maxLength={80}
                        placeholder={field.hint}
                        onChange={event => setDraft({ ...draft, name: event.target.value })}
                      />
                    ) : (
                      <textarea
                        value={draft[field.key]}
                        maxLength={field.key === 'worldBook' ? 256000 : 16000}
                        rows={field.key === 'examples' ? 4 : 2}
                        placeholder={field.hint}
                        onChange={event => setDraft({ ...draft, [field.key]: event.target.value })}
                      />
                    )}
                  </label>
                ))}

                <div className="persona-wb-quick">
                  <strong>快速追加世界书条目</strong>
                  <input
                    value={wbKeys}
                    placeholder="触发关键词（逗号分隔，例如：海边, 咖啡）"
                    onChange={event => setWbKeys(event.target.value)}
                  />
                  <input
                    value={wbContent}
                    placeholder="命中关键词时补充的专属设定或回忆"
                    onChange={event => setWbContent(event.target.value)}
                  />
                  <button type="button" onClick={addWorldBookEntry}>加入世界书</button>
                </div>

                <p>可在设定中使用 {'{{char}}'} 和 {'{{user}}'}；角色设定不会修改资料、记忆或工具权限。</p>

                <div className="persona-actions">
                  <button type="button" onClick={save} disabled={!draft.name.trim()}>保存</button>
                  {draft.id && (
                    <button type="button" onClick={duplicateDraft}>复制一份</button>
                  )}
                  {draft.id && draft.id !== activeId && (
                    <button
                      type="button"
                      onClick={() => void action(async () => {
                        const result = await runtimeBridge.companionDashboard({ operation: 'personaActivate', id: draft.id })
                        if (result.ok !== true) throw new Error('activate failed')
                        await load(draft.id)
                      }, '已切换桌宠角色。')}
                    >
                      启用此角色
                    </button>
                  )}
                  {draft.id && cards.length > 1 && (
                    <button
                      type="button"
                      onClick={() => {
                        if (window.confirm('删除这张角色卡？')) {
                          void action(async () => {
                            const result = await runtimeBridge.companionDashboard({ operation: 'personaDelete', id: draft.id })
                            if (result.ok !== true) throw new Error('delete failed')
                            await load()
                          }, '角色卡已删除。')
                        }
                      }}
                    >
                      删除
                    </button>
                  )}
                </div>
              </fieldset>
            </>
          )}

          {tab === 'transfer' && (
            <section className="persona-transfer">
              <h3>本地导入与导出</h3>
              <p>仅支持导入导出你本机的 PNG 或 JSON 角色卡文本设定与世界书。</p>
              <label>
                选择本地 PNG 或 JSON 文件
                <input
                  type="file"
                  accept=".png,.json,image/png,application/json"
                  onChange={event => { void chooseFile(event.target.files?.[0]); event.target.value = '' }}
                />
              </label>
              <textarea
                value={json}
                rows={7}
                onChange={event => setJson(event.target.value)}
                placeholder="粘贴角色卡 JSON"
              />
              <div className="persona-actions">
                <button type="button" onClick={importJson}>导入 JSON 草稿</button>
                <button type="button" onClick={exportJson}>生成导出 JSON</button>
              </div>
            </section>
          )}
        </>
      )}
    </section>
  )
}
