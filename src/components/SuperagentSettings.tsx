import { useEffect, useRef, useState } from 'react'
import { runtimeBridge } from '../platform/native'
import { pollWhileVisible } from '../pollWhileVisible'

type Source = { enabled?: boolean; repo?: string; botLogin?: string; chats?: string[]; botOpenId?: string; allowedActors?: string[] }
type Config = { github?: Source; feishu?: Source; proactive?: { mode?: 'off' | 'observe' | 'auto'; rules?: Array<{ id: string; text: string }> } }
const list = (text: string): string[] => text.split(/[\n,，]/).map(value => value.trim()).filter(Boolean)

/** Explicit source scope and credentials; existing passwords are never returned to this view. */
export function SuperagentSettings({ visible = true }: { visible?: boolean }) {
  const initialized = useRef(false)
  const revision = useRef('initial')
  const [saving, setSaving] = useState(false)
  const [conflict, setConflict] = useState(false)
  const [reread, setReread] = useState(0)
  const [config, setConfig] = useState<Config>({})
  const [credentials, setCredentials] = useState<Record<string, string>>({})
  const [configured, setConfigured] = useState<Record<string, boolean>>({})
  const [removed, setRemoved] = useState<string[]>([])
  const [message, setMessage] = useState('')
  const [diagnostics, setDiagnostics] = useState<Record<string, unknown>>({})
  const [unapproved, setUnapproved] = useState<Array<{ source: 'github' | 'feishu'; actorId: string }>>([])
  const [githubActors, setGithubActors] = useState('')
  const [feishuActors, setFeishuActors] = useState('')
  const [chatIds, setChatIds] = useState('')
  const [ruleText, setRuleText] = useState('')
  useEffect(() => {
    if (!visible) return
    return pollWhileVisible(5000, async current => {
      try {
        const value = await runtimeBridge.companionDashboard({ operation: 'superagentSettings' })
        if (!current()) return
        setDiagnostics((value.diagnostics || {}) as Record<string, unknown>)
        setUnapproved(Array.isArray(value.unapproved) ? value.unapproved as Array<{ source: 'github' | 'feishu'; actorId: string }> : [])
        if (initialized.current) return
        const saved = (value.config || {}) as Config
        revision.current = typeof value.revision === 'string' ? value.revision : 'initial'
        setConfig(saved)
        setGithubActors((saved.github?.allowedActors || []).join(','))
        setFeishuActors((saved.feishu?.allowedActors || []).join(','))
        setChatIds((saved.feishu?.chats || []).join(','))
        setRuleText((saved.proactive?.rules || []).map(rule => rule.text).join('\n'))
        setConfigured((value.credentials || {}) as Record<string, boolean>)
        initialized.current = true
      } catch { if (current() && !initialized.current) setMessage('未能读取渠道设置。') }
    })
  }, [visible,reread])
  const change = (source: 'github' | 'feishu', update: Partial<Source>) => setConfig(old => ({ ...old, [source]: { ...old[source], ...update } }))
  const save = async () => {
    if (!initialized.current || saving || conflict) return
    setSaving(true)
    try {
      const updates = Object.fromEntries(Object.entries(credentials).filter(([, value]) => value.trim()))
      for (const key of removed) updates[key] = ''
      const saved = { github: { ...config.github, allowedActors: list(githubActors) }, feishu: { ...config.feishu, chats: list(chatIds), allowedActors: list(feishuActors) }, proactive: { mode: config.proactive?.mode || 'off', rules: ruleText.split('\n').map(text => text.trim()).filter(Boolean).map((text, index) => ({ id: `rule-${index + 1}`, text })) } }
      const value = await runtimeBridge.companionDashboard({ operation: 'superagentSettingsSave', expectedRevision: revision.current, config: saved, credentials: updates })
      if (value.conflict === true) {
        setConflict(true)
        setMessage('渠道设置已在其他窗口更新。请重新载入最新授权，再决定是否修改。')
        return
      }
      revision.current = String(value.revision)
      setConfig(saved)
      setConfigured((value.credentials || {}) as Record<string, boolean>); setCredentials({}); setRemoved([]); setMessage('设置已保存。')
    } catch { setMessage('设置未能保存，请检查输入。') }
    finally { setSaving(false) }
  }
  const github = config.github || {}, feishu = config.feishu || {}
  const removalButton = (keys: string[]) => keys.some(key => configured[key]) && <button type="button"
    aria-pressed={removed.includes(keys[0])} onClick={() => setRemoved(current => current.includes(keys[0])
      ? current.filter(key => !keys.includes(key)) : [...current,...keys])}>
    {removed.includes(keys[0]) ? '撤销移除凭据' : '保存时移除本机凭据'}
  </button>
  return <details><summary>跨渠道任务设置</summary>
    <p>只处理所选来源中允许的发送人。新发送人需要在此明确添加，凭据保存在设备内。</p>
    {(Object.keys((diagnostics.errors || {}) as object).length > 0 || (Array.isArray(diagnostics.problems) && diagnostics.problems.length > 0)) &&
      <p role="status">渠道或任务暂时不可用，请检查配置和授权。{JSON.stringify(diagnostics.errors)} {JSON.stringify(diagnostics.problems)}</p>}
    {typeof diagnostics.pendingReplies === 'number' && diagnostics.pendingReplies > 0 && <p>待回传消息：{diagnostics.pendingReplies}</p>}
    <fieldset disabled={saving || !initialized.current}><legend>GitHub</legend>
      <label><input type="checkbox" checked={!!github.enabled} onChange={e => change('github', { enabled: e.target.checked })} />启用 GitHub</label>
      <label>仓库（owner/name）<input value={github.repo || ''} onChange={e => change('github', { repo: e.target.value })} /></label>
      <label>机器人账号<input value={github.botLogin || ''} onChange={e => change('github', { botLogin: e.target.value })} /></label>
      <label>允许的 GitHub 用户（逗号分隔）<input value={githubActors} onChange={e => setGithubActors(e.target.value)} /></label>
      <label>GitHub 令牌{removed.includes('githubToken') ? '（保存后移除）' : configured.githubToken ? '（已保存，留空保留）' : ''}<input disabled={removed.includes('githubToken')} type="password" value={credentials.githubToken || ''} onChange={e => setCredentials(old => ({ ...old, githubToken: e.target.value }))} /></label>
      {removalButton(['githubToken'])}
    </fieldset>
    <fieldset disabled={saving || !initialized.current}><legend>飞书</legend>
      <label><input type="checkbox" checked={!!feishu.enabled} onChange={e => change('feishu', { enabled: e.target.checked })} />启用飞书</label>
      <label>群聊 ID（逗号分隔）<input value={chatIds} onChange={e => setChatIds(e.target.value)} /></label>
      <label>机器人 open ID<input value={feishu.botOpenId || ''} onChange={e => change('feishu', { botOpenId: e.target.value })} /></label>
      <label>允许的发送人 open ID（逗号分隔）<input value={feishuActors} onChange={e => setFeishuActors(e.target.value)} /></label>
      <label>应用 ID{removed.includes('feishuAppId') ? '（保存后移除）' : configured.feishuAppId ? '（已保存，留空保留）' : ''}<input disabled={removed.includes('feishuAppId')} value={credentials.feishuAppId || ''} onChange={e => setCredentials(old => ({ ...old, feishuAppId: e.target.value }))} /></label>
      <label>应用密钥{removed.includes('feishuAppSecret') ? '（保存后移除）' : configured.feishuAppSecret ? '（已保存，留空保留）' : ''}<input disabled={removed.includes('feishuAppSecret')} type="password" value={credentials.feishuAppSecret || ''} onChange={e => setCredentials(old => ({ ...old, feishuAppSecret: e.target.value }))} /></label>
      {removalButton(['feishuAppId','feishuAppSecret'])}
    </fieldset>
    <fieldset disabled={saving || !initialized.current}><legend>主动任务</legend>
      <label>处理方式<select value={config.proactive?.mode || 'off'} onChange={e => setConfig(old => ({ ...old, proactive: { ...old.proactive, mode: e.target.value as 'off' | 'observe' | 'auto' } }))}>
        <option value="off">关闭</option><option value="observe">仅观察</option><option value="auto">按规则执行</option></select></label>
      <label>允许的任务规则，每行一条<textarea value={ruleText} onChange={e => setRuleText(e.target.value)} /></label>
      <p>仅处理已授权发送人的消息；模型会判断是否符合规则。观察模式只记录建议。</p>
    </fieldset>
    {unapproved.length > 0 && <div><p>待确认的发送人：确认后请对方重新发送任务。</p>{unapproved.map(actor =>
      <button key={`${actor.source}:${actor.actorId}`} type="button" onClick={() => {
        if (actor.source === 'github') setGithubActors(old => [...new Set([...list(old), actor.actorId])].join(','))
        else setFeishuActors(old => [...new Set([...list(old), actor.actorId])].join(','))
        setUnapproved(old => old.filter(item => item.source !== actor.source || item.actorId !== actor.actorId))
        setMessage('已添加到待保存的允许名单，请点击保存。')
      }}>{actor.source}：允许 {actor.actorId}</button>)}</div>}
    <button type="button" disabled={!initialized.current || saving || conflict} onClick={() => { void save() }}>保存渠道设置</button>
    {conflict && <button type="button" onClick={() => {
      initialized.current = false; setCredentials({}); setRemoved([]); setConflict(false); setMessage(''); setReread(n => n+1)
    }}>放弃草稿并载入最新设置</button>}
    {message && <p role="status">{message}</p>}
  </details>
}
