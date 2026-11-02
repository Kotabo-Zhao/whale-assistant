import { useEffect, useState } from 'react'
import { runtimeBridge } from '../platform/native'

type Source = { enabled?: boolean; repo?: string; botLogin?: string; chats?: string[]; botOpenId?: string; allowedActors?: string[] }
type Config = { github?: Source; feishu?: Source; proactive?: { mode?: 'off' | 'observe' | 'auto'; rules?: Array<{ id: string; text: string }> } }
const list = (text: string): string[] => text.split(/[\n,，]/).map(value => value.trim()).filter(Boolean)

/** Explicit source scope and credentials; existing passwords are never returned to this view. */
export function SuperagentSettings() {
  const [config, setConfig] = useState<Config>({})
  const [credentials, setCredentials] = useState<Record<string, string>>({})
  const [configured, setConfigured] = useState<Record<string, boolean>>({})
  const [message, setMessage] = useState('')
  const [diagnostics, setDiagnostics] = useState<Record<string, unknown>>({})
  const [unapproved, setUnapproved] = useState<Array<{ source: 'github' | 'feishu'; actorId: string }>>([])
  const [githubActors, setGithubActors] = useState('')
  const [feishuActors, setFeishuActors] = useState('')
  const [chatIds, setChatIds] = useState('')
  const [ruleText, setRuleText] = useState('')
  useEffect(() => { void runtimeBridge.companionDashboard({ operation: 'superagentSettings' }).then(value => {
    const saved = (value.config || {}) as Config
    setDiagnostics((value.diagnostics || {}) as Record<string, unknown>)
    setUnapproved(Array.isArray(value.unapproved) ? value.unapproved as Array<{ source: 'github' | 'feishu'; actorId: string }> : [])
    setConfig(saved); setGithubActors((saved.github?.allowedActors || []).join(',')); setFeishuActors((saved.feishu?.allowedActors || []).join(',')); setChatIds((saved.feishu?.chats || []).join(',')); setRuleText((saved.proactive?.rules || []).map(rule => rule.text).join('\n')); setConfigured((value.credentials || {}) as Record<string, boolean>)
  }).catch(() => setMessage('未能读取渠道设置。')) }, [])
  useEffect(() => {
    const timer = window.setInterval(() => {
      void runtimeBridge.companionDashboard({ operation: 'superagentSettings' }).then(value => {
        setDiagnostics((value.diagnostics || {}) as Record<string, unknown>)
        if (Array.isArray(value.unapproved)) setUnapproved(value.unapproved as Array<{ source: 'github' | 'feishu'; actorId: string }>)
      }).catch(() => {})
    }, 5000)
    return () => window.clearInterval(timer)
  }, [])
  const change = (source: 'github' | 'feishu', update: Partial<Source>) => setConfig(old => ({ ...old, [source]: { ...old[source], ...update } }))
  const save = async () => {
    try {
      const updates = Object.fromEntries(Object.entries(credentials).filter(([, value]) => value.trim()))
      const saved = { github: { ...config.github, allowedActors: list(githubActors) }, feishu: { ...config.feishu, chats: list(chatIds), allowedActors: list(feishuActors) }, proactive: { mode: config.proactive?.mode || 'off', rules: ruleText.split('\n').map(text => text.trim()).filter(Boolean).map((text, index) => ({ id: `rule-${index + 1}`, text })) } }
      const value = await runtimeBridge.companionDashboard({ operation: 'superagentSettingsSave', config: saved, credentials: updates })
      setConfig(saved)
      setConfigured((value.credentials || {}) as Record<string, boolean>); setCredentials({}); setMessage('设置已保存。')
    } catch { setMessage('设置未能保存，请检查输入。') }
  }
  const github = config.github || {}, feishu = config.feishu || {}
  return <details><summary>跨渠道任务设置</summary>
    <p>只处理所选来源中允许的发送人。新发送人需要在此明确添加，凭据保存在设备内。</p>
    {(Object.keys((diagnostics.errors || {}) as object).length > 0 || (Array.isArray(diagnostics.problems) && diagnostics.problems.length > 0)) &&
      <p role="status">渠道或任务暂时不可用，请检查配置和授权。{JSON.stringify(diagnostics.errors)} {JSON.stringify(diagnostics.problems)}</p>}
    {typeof diagnostics.pendingReplies === 'number' && diagnostics.pendingReplies > 0 && <p>待回传消息：{diagnostics.pendingReplies}</p>}
    <fieldset><legend>GitHub</legend>
      <label><input type="checkbox" checked={!!github.enabled} onChange={e => change('github', { enabled: e.target.checked })} />启用 GitHub</label>
      <label>仓库（owner/name）<input value={github.repo || ''} onChange={e => change('github', { repo: e.target.value })} /></label>
      <label>机器人账号<input value={github.botLogin || ''} onChange={e => change('github', { botLogin: e.target.value })} /></label>
      <label>允许的 GitHub 用户（逗号分隔）<input value={githubActors} onChange={e => setGithubActors(e.target.value)} /></label>
      <label>GitHub 令牌{configured.githubToken ? '（已保存，留空保留）' : ''}<input type="password" value={credentials.githubToken || ''} onChange={e => setCredentials(old => ({ ...old, githubToken: e.target.value }))} /></label>
    </fieldset>
    <fieldset><legend>飞书</legend>
      <label><input type="checkbox" checked={!!feishu.enabled} onChange={e => change('feishu', { enabled: e.target.checked })} />启用飞书</label>
      <label>群聊 ID（逗号分隔）<input value={chatIds} onChange={e => setChatIds(e.target.value)} /></label>
      <label>机器人 open ID<input value={feishu.botOpenId || ''} onChange={e => change('feishu', { botOpenId: e.target.value })} /></label>
      <label>允许的发送人 open ID（逗号分隔）<input value={feishuActors} onChange={e => setFeishuActors(e.target.value)} /></label>
      <label>应用 ID{configured.feishuAppId ? '（已保存，留空保留）' : ''}<input value={credentials.feishuAppId || ''} onChange={e => setCredentials(old => ({ ...old, feishuAppId: e.target.value }))} /></label>
      <label>应用密钥{configured.feishuAppSecret ? '（已保存，留空保留）' : ''}<input type="password" value={credentials.feishuAppSecret || ''} onChange={e => setCredentials(old => ({ ...old, feishuAppSecret: e.target.value }))} /></label>
    </fieldset>
    <fieldset><legend>主动任务</legend>
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
    <button type="button" onClick={() => { void save() }}>保存渠道设置</button>{message && <p role="status">{message}</p>}
  </details>
}
