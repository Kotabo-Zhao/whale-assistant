import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { downloadOfficialCard, loadOfficialCards, readPngCard, type CatalogCard } from './sillyTavernCatalog'
import { downloadCommunityCard, searchCommunity, type CommunityCard } from './communityCatalog'
import './CompanionPersona.css'

type Card = { id: string; name: string; description: string; personality: string; scenario: string; examples: string; greeting: string; systemPrompt: string; postHistory: string; worldBook: string }
const blank = (): Card => ({ id: '', name: '', description: '', personality: '', scenario: '', examples: '', greeting: '', systemPrompt: '', postHistory: '', worldBook: '' })
const fields: { key: keyof Omit<Card, 'id'>; label: string; hint: string }[] = [
  { key: 'name', label: '角色名', hint: '例如：鲸鱼娘' },
  { key: 'description', label: '角色简介', hint: '身份、背景与重要设定' },
  { key: 'personality', label: '性格与说话方式', hint: '性格特点、语气与表达习惯' },
  { key: 'scenario', label: '情境', hint: '角色与用户的关系、日常相处场景' },
  { key: 'examples', label: '示例对话', hint: '写几句示例，帮助角色保持说话风格' },
  { key: 'greeting', label: '开场白', hint: '开启空白桌宠对话时显示' },
  { key: 'systemPrompt', label: '角色附加指引', hint: '角色卡中的 system_prompt，作为桌宠角色设定使用' },
  { key: 'postHistory', label: '对话后指引', hint: '角色卡中的 post_history_instructions' },
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
  const [catalog, setCatalog] = useState<CatalogCard[] | null>(null)
  const [catalogQuery, setCatalogQuery] = useState('')
  const [tab, setTab] = useState<'cards' | 'discover' | 'transfer'>('cards')
  const [source, setSource] = useState<'community' | 'official'>('community')
  const [community, setCommunity] = useState<CommunityCard[] | null>(null)
  const [communityQuery, setCommunityQuery] = useState('')
  const [communityPage, setCommunityPage] = useState(1)

  const load = async (selected?: string) => {
    const result = await runtimeBridge.companionDashboard({ operation: 'personaList' })
    if (!Array.isArray(result.cards) || typeof result.activeId !== 'string') throw new Error('角色卡格式无效')
    const next = (result.cards as Card[]).map(card => ({ ...blank(), ...card }))
    setCards(next); setActiveId(result.activeId)
    setDraft(next.find(card => card.id === (selected ?? result.activeId)) ?? next[0] ?? blank())
  }
  useEffect(() => { void load().catch(() => setMessage('无法读取角色卡，请稍后重试。')).finally(() => setLoading(false)) }, [])
  const action = async (fn: () => Promise<void>, ok: string, failed = '操作未完成，请检查内容后重试。') => {
    setBusy(true); setMessage('')
    try { await fn(); setMessage(ok) } catch { setMessage(failed) } finally { setBusy(false) }
  }
  const save = () => void action(async () => {
    const result = await runtimeBridge.companionDashboard({ operation: 'personaSave', card: draft })
    const saved = result.card as Card | undefined
    if (result.ok !== true || !saved?.id) throw new Error('save failed')
    await load(saved.id)
  }, '角色卡已保存。')
  const importCard = (root: Record<string, unknown>) => {
      const data = typeof root.data === 'object' && root.data !== null ? root.data as Record<string, unknown> : root
      const str = (key: string) => typeof data[key] === 'string' ? data[key] : ''
      const book = data.character_book
      const card: Card = { id: '', name: str('name'), description: str('description'), personality: str('personality'), scenario: str('scenario'), examples: str('mes_example') || str('examples'), greeting: str('first_mes') || str('greeting'), systemPrompt: str('system_prompt') || str('systemPrompt'), postHistory: str('post_history_instructions') || str('postHistory'), worldBook: book && typeof book === 'object' ? JSON.stringify(book) : str('worldBook') }
      if (!card.name.trim()) throw new Error('missing name')
      setDraft(card); setTab('cards'); setMessage('已导入草稿，请检查并保存。')
  }
  const importJson = () => {
    try {
      importCard(JSON.parse(json) as Record<string, unknown>)
    } catch { setMessage('角色卡 JSON 无效或缺少角色名。') }
  }
  const chooseFile = async (file: File | undefined) => {
    if (!file) return
    try {
      if (file.size > 16 * 1024 * 1024) throw new Error('too large')
      const bytes = new Uint8Array(await file.arrayBuffer())
      const root = file.name.toLowerCase().endsWith('.png') ? readPngCard(bytes) : JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>
      importCard(root)
    } catch { setMessage('无法读取角色卡，请选择有效的 PNG 或 JSON 文件。') }
  }
  const exportJson = () => {
    try {
      const book: unknown = draft.worldBook.trim() ? JSON.parse(draft.worldBook) : undefined
      setJson(JSON.stringify({ spec: 'chara_card_v2', spec_version: '2.0', data: { name: draft.name, description: draft.description, personality: draft.personality, scenario: draft.scenario, first_mes: draft.greeting, mes_example: draft.examples, system_prompt: draft.systemPrompt, post_history_instructions: draft.postHistory, ...(book !== undefined ? { character_book: book } : {}) } }, null, 2))
    } catch { setMessage('世界书 JSON 无效，请修正后导出。'); return }
    setMessage('JSON 已生成，可复制保存。')
  }
  return <section className="persona-page" aria-labelledby="persona-title">
    <header className="persona-hero"><div><span className="persona-eyebrow">CHARACTER STUDIO</span><h2 id="persona-title">鲸鱼娘人格</h2><p>让她以你喜欢的方式陪伴。角色设定与长期记忆分开保存。</p></div><button type="button" onClick={onBack}>返回</button></header>
    <nav className="persona-tabs" aria-label="角色管理"><button type="button" aria-pressed={tab === 'cards'} onClick={() => setTab('cards')}>我的角色</button><button type="button" aria-pressed={tab === 'discover'} onClick={() => setTab('discover')}>发现角色</button><button type="button" aria-pressed={tab === 'transfer'} onClick={() => setTab('transfer')}>导入导出</button></nav>
    {message && <p role="status" className="persona-notice">{message}</p>}
    {loading ? <p>正在读取角色卡…</p> : <>{tab === 'cards' && <><div className="persona-section-heading"><div><h3>我的角色</h3><p>当前角色会在每次桌宠回复时提供给模型。</p></div><button type="button" onClick={() => setDraft(blank())}>＋ 新建</button></div><div className="persona-cards">{cards.map(card => <button type="button" key={card.id} className={draft.id === card.id ? 'is-selected' : ''} onClick={() => setDraft({ ...card })}><span className="persona-avatar">{card.name.slice(0, 1)}</span><strong>{card.name}</strong><small>{activeId === card.id ? '● 使用中' : '选择编辑'}</small></button>)}</div>
      <fieldset disabled={busy} className="persona-editor"><legend>{draft.id ? '编辑角色卡' : '新建角色卡'}</legend>
        {fields.map(field => <label key={field.key}>{field.label}{field.key === 'name' ? <input value={draft.name} maxLength={80} placeholder={field.hint} onChange={event => setDraft({ ...draft, name: event.target.value })} /> : <textarea value={draft[field.key]} maxLength={field.key === 'worldBook' ? 256000 : 16000} rows={field.key === 'examples' ? 4 : 2} placeholder={field.hint} onChange={event => setDraft({ ...draft, [field.key]: event.target.value })} />}</label>)}
        <p>可在设定中使用 {'{{char}}'} 和 {'{{user}}'}；角色设定不会修改资料、记忆或工具权限。</p>
        <div className="persona-actions"><button type="button" onClick={save} disabled={!draft.name.trim()}>保存</button>{draft.id && draft.id !== activeId && <button type="button" onClick={() => void action(async () => { const result = await runtimeBridge.companionDashboard({ operation: 'personaActivate', id: draft.id }); if (result.ok !== true) throw new Error('activate failed'); await load(draft.id) }, '已切换桌宠角色。')}>启用此角色</button>}{draft.id && cards.length > 1 && <button type="button" onClick={() => { if (window.confirm('删除这张角色卡？')) void action(async () => { const result = await runtimeBridge.companionDashboard({ operation: 'personaDelete', id: draft.id }); if (result.ok !== true) throw new Error('delete failed'); await load() }, '角色卡已删除。') }}>删除</button>}</div>
      </fieldset></>}
      {tab === 'discover' && <section className="persona-discover"><div className="persona-section-heading"><div><h3>发现角色</h3><p>下载后先检查草稿，再选择保存或启用。</p></div></div><div className="persona-source"><button type="button" aria-pressed={source === 'community'} onClick={() => setSource('community')}>社区 · Chub</button><button type="button" aria-pressed={source === 'official'} onClick={() => setSource('official')}>SillyTavern 精选</button></div>
        {source === 'community' ? <><p>第三方社区；搜索词会发送给 Chub。支持角色文字设定与世界书。</p><form className="persona-search" onSubmit={event => { event.preventDefault(); void action(async () => { setCommunity(await searchCommunity(communityQuery, 1)); setCommunityPage(1) }, '社区搜索完成。', '无法连接社区目录，请检查网络后重试。') }}><input value={communityQuery} onChange={event => setCommunityQuery(event.target.value)} placeholder="搜索社区角色" aria-label="搜索社区角色" /><button disabled={busy} type="submit">搜索</button></form><div className="persona-catalog">{community?.map(card => <article key={card.path}><span className="persona-avatar">{card.name.slice(0, 1)}</span><strong>{card.name}</strong><small>{card.path.split('/')[0]}</small><p>{card.description || '社区角色卡'}</p><button type="button" disabled={busy} onClick={() => void action(async () => { importCard(await downloadCommunityCard(card)) }, '角色已下载到草稿，请检查并保存。', '角色不可下载或社区服务暂不可用。')}>下载并查看</button></article>)}</div>{community && community.length === 0 && <p>没有找到角色，可以换个关键词。</p>}{community && community.length === 20 && <button type="button" disabled={busy} onClick={() => void action(async () => { const next = communityPage + 1; setCommunity(await searchCommunity(communityQuery, next)); setCommunityPage(next) }, '已加载下一页。', '无法加载下一页。')}>下一页</button>}</> : <><p>SillyTavern 维护的社区精选；支持角色文字设定与世界书，不导入图片。</p><button type="button" disabled={busy} onClick={() => void action(async () => { setCatalog(await loadOfficialCards()) }, '官方角色目录已更新。', '无法连接官方目录，请检查网络后重试。')}>加载官方目录</button>{catalog && <><label className="persona-search-label">搜索精选<input value={catalogQuery} onChange={event => setCatalogQuery(event.target.value)} placeholder="搜索名称或介绍" /></label><div className="persona-catalog">{catalog.filter(card => `${card.name} ${card.description}`.toLowerCase().includes(catalogQuery.toLowerCase())).map(card => <article key={card.id}><span className="persona-avatar">{card.name.slice(0, 1)}</span><strong>{card.name}</strong><small>SillyTavern 精选</small><p>{card.description}</p><button type="button" disabled={busy} onClick={() => void action(async () => { importCard(await downloadOfficialCard(card)) }, '角色已下载到草稿，请检查并保存。', '角色卡下载失败或格式不受支持，请稍后重试。')}>下载并查看</button></article>)}</div>{catalog.length === 0 && <p>官方目录目前没有角色卡。</p>}</>}</>}
      </section>}
      {tab === 'transfer' && <section className="persona-transfer"><h3>导入与导出</h3><p>支持 SillyTavern PNG 或 JSON 角色卡的文字设定、附加指引与世界书；图片和其他扩展不导入。导出的 JSON 可复制保存。</p><label>选择 PNG 或 JSON 文件<input type="file" accept=".png,.json,image/png,application/json" onChange={event => { void chooseFile(event.target.files?.[0]); event.target.value = '' }} /></label><textarea value={json} rows={7} onChange={event => setJson(event.target.value)} placeholder="粘贴角色卡 JSON" /><div className="persona-actions"><button type="button" onClick={importJson}>导入 JSON 草稿</button><button type="button" onClick={exportJson}>生成导出 JSON</button></div></section>}</>}
  </section>
}
