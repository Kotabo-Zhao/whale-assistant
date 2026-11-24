import { useCallback, useEffect, useState } from 'react'
import { ChevronDown, Cpu, Puzzle, Sparkles } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'

type SkillRow = { slug: string; name: string; description: string; enabled: boolean; sourceUrl?: string }
type McpRow = { id: string; name: string; url: string; enabled: boolean; toolCount: number; tools?: string[] }
type UsageBreakdown = {
  totalTokens: number
  cacheHitRatio: number
  compressionRatio?: number
  savedChars?: number
  estimatedYuan: number
  chatTokens: number
  workerTokens: number
  phoneTokens: number
  petTokens?: number
  bgTokens?: number
}
type ReadinessState = {
  overlay?: boolean
  accessibility?: boolean
  notifications?: boolean
  shizuku?: boolean
  modelKey?: boolean
  voiceKey?: boolean
}

function fmtTok(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

export function WhalePowerHub() {
  const [skills, setSkills] = useState<SkillRow[]>([])
  const [mcpServers, setMcpServers] = useState<McpRow[]>([])
  const [skillUrl, setSkillUrl] = useState('')
  const [mcpUrl, setMcpUrl] = useState('')
  const [mcpName, setMcpName] = useState('')
  const [mcpAuth, setMcpAuth] = useState('')
  const [extMsg, setExtMsg] = useState('')
  const [extBusy, setExtBusy] = useState(false)

  const [usage, setUsage] = useState<UsageBreakdown | null>(null)
  const [readiness, setReadiness] = useState<ReadinessState | null>(null)
  const [nativeNotifOn, setNativeNotifOn] = useState(true)
  const [floatingIslandOn, setFloatingIslandOn] = useState(false)
  const [fastModel, setFastModel] = useState('')
  const [deepModel, setDeepModel] = useState('')
  const [routerMsg, setRouterMsg] = useState('')
  const [memMsg, setMemMsg] = useState('')

  const refreshExtensions = useCallback(async () => {
    try {
      const res = await runtimeBridge.companionDashboard({ operation: 'extensionsList' })
      if (Array.isArray(res.skills)) setSkills(res.skills as SkillRow[])
      if (Array.isArray(res.mcpServers)) setMcpServers(res.mcpServers as McpRow[])
    } catch { /* ignore in browser preview */ }
  }, [])

  const refreshUsage = useCallback(async () => {
    try {
      const res = await runtimeBridge.companionDashboard({ operation: 'usageAndRouterGet' })
      if (res.usage && typeof res.usage === 'object') setUsage(res.usage as UsageBreakdown)
      if (typeof res.fastModel === 'string') setFastModel(res.fastModel)
      if (typeof res.deepModel === 'string') setDeepModel(res.deepModel)
      const probe = await runtimeBridge.companionDashboard({ operation: 'readinessProbe' })
      if (probe && typeof probe === 'object') setReadiness(probe as ReadinessState)
      const island = await runtimeBridge.companionDashboard({ operation: 'liveIslandPrefs' })
      if (typeof island.nativeNotif === 'boolean') setNativeNotifOn(island.nativeNotif)
      if (typeof island.floatingIsland === 'boolean') setFloatingIslandOn(island.floatingIsland)
    } catch { /* ignore in browser preview */ }
  }, [])

  useEffect(() => {
    void refreshExtensions()
    void refreshUsage()
  }, [refreshExtensions, refreshUsage])

  const installSkill = async () => {
    if (!skillUrl.trim()) return
    setExtBusy(true)
    setExtMsg('')
    try {
      const res = await runtimeBridge.companionDashboard({ operation: 'skillInstall', url: skillUrl.trim() })
      setSkillUrl('')
      setExtMsg(`已安装技能：${typeof res.name === 'string' ? res.name : 'Skill'}`)
      await refreshExtensions()
    } catch (e) {
      setExtMsg(e instanceof Error ? e.message : '安装 Skill 失败')
    } finally {
      setExtBusy(false)
    }
  }

  const manageSkill = async (name: string, action: 'enable' | 'disable' | 'delete') => {
    setExtBusy(true)
    setExtMsg('')
    try {
      await runtimeBridge.companionDashboard({ operation: 'skillManage', name, action })
      await refreshExtensions()
    } catch {
      setExtMsg('更新 Skill 状态失败')
    } finally {
      setExtBusy(false)
    }
  }

  const addMcp = async () => {
    if (!mcpUrl.trim()) return
    setExtBusy(true)
    setExtMsg('')
    try {
      const res = await runtimeBridge.companionDashboard({
        operation: 'mcpAdd',
        url: mcpUrl.trim(),
        name: mcpName.trim(),
        authHeader: mcpAuth.trim(),
      })
      setMcpUrl('')
      setMcpName('')
      setMcpAuth('')
      setExtMsg(`已接入 MCP：${typeof res.name === 'string' ? res.name : 'Server'}（${Number(res.toolCount ?? 0)} 个工具）`)
      await refreshExtensions()
    } catch (e) {
      setExtMsg(e instanceof Error ? e.message : '接入 MCP 失败')
    } finally {
      setExtBusy(false)
    }
  }

  const manageMcp = async (id: string, action: 'refresh' | 'enable' | 'disable' | 'remove') => {
    setExtBusy(true)
    setExtMsg('')
    try {
      await runtimeBridge.companionDashboard({ operation: 'mcpManage', id, action })
      await refreshExtensions()
    } catch {
      setExtMsg('操作 MCP 服务器失败')
    } finally {
      setExtBusy(false)
    }
  }

  const saveRouter = async () => {
    setRouterMsg('')
    try {
      await runtimeBridge.companionDashboard({ operation: 'routerConfigSave', fastModel, deepModel })
      setRouterMsg('分场景模型已生效')
    } catch {
      setRouterMsg('保存失败')
    }
  }

  const compactMemory = async () => {
    setMemMsg('')
    try {
      const res = await runtimeBridge.companionDashboard({ operation: 'memoryCompact' })
      const removed = Number(res.removed ?? 0)
      const scanned = Number(res.scanned ?? 0)
      setMemMsg(removed > 0 ? `已扫描 ${scanned} 条，清理 ${removed} 条重复/空记忆` : `已扫描 ${scanned} 条，记忆库非常干净`)
    } catch {
      setMemMsg('记忆体检失败，请在安卓应用中重试')
    }
  }

  const usageSummary = usage
    ? `${fmtTok(usage.totalTokens)} · ¥${usage.estimatedYuan.toFixed(2)}`
    : t('按需查看')

  return (
    <div className="whale-power-stack" aria-label={t('扩展、路由与记忆体检')}>
      <details className="whale-power-card">
        <summary className="whale-power-summary">
          <span className="whale-power-title">
            <Puzzle size={15} aria-hidden="true" />
            <strong>{t('技能与 MCP 扩展')}</strong>
          </span>
          <span className="whale-power-meta">
            <span className="whale-power-badge">{skills.length} Skill · {mcpServers.length} MCP</span>
            <ChevronDown size={15} className="whale-power-chevron" aria-hidden="true" />
          </span>
        </summary>
        <div className="whale-power-body">
          <div className="whale-power-group">
            <label className="whale-power-label">{t('安装 Skill（支持 GitHub 仓库或 SKILL.md 链接）')}</label>
            <div className="whale-voice-row">
              <input
                value={skillUrl}
                placeholder={t('粘贴 GitHub 或 SKILL.md 地址')}
                onChange={e => setSkillUrl(e.target.value)}
              />
              <button type="button" className="whale-perm-chip" disabled={extBusy || !skillUrl.trim()} onClick={() => { void installSkill() }}>
                {t('安装')}
              </button>
            </div>
          </div>
          {skills.length > 0 && (
            <div className="whale-power-list">
              {skills.map(sk => (
                <div key={sk.slug} className="whale-power-item">
                  <span><strong>{sk.name}</strong>{sk.enabled ? '' : ` · ${t('已停用')}`}</span>
                  <div className="whale-power-item-actions">
                    <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageSkill(sk.slug, sk.enabled ? 'disable' : 'enable') }}>
                      {sk.enabled ? t('停用') : t('启用')}
                    </button>
                    <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageSkill(sk.slug, 'delete') }}>
                      {t('删除')}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
          <div className="whale-power-group">
            <label className="whale-power-label">{t('接入远程 MCP 服务器')}</label>
            <div className="whale-power-grid">
              <input value={mcpName} placeholder={t('服务器名称')} onChange={e => setMcpName(e.target.value)} />
              <input value={mcpUrl} placeholder={t('HTTPS 地址')} onChange={e => setMcpUrl(e.target.value)} />
            </div>
            <div className="whale-voice-row">
              <input
                type="password"
                value={mcpAuth}
                placeholder={t('可选 Authorization（本地加密，不进聊天）')}
                onChange={e => setMcpAuth(e.target.value)}
              />
              <button type="button" className="whale-perm-chip" disabled={extBusy || !mcpUrl.trim()} onClick={() => { void addMcp() }}>
                {t('接入')}
              </button>
            </div>
          </div>
          {mcpServers.length > 0 && (
            <div className="whale-power-list">
              {mcpServers.map(sv => (
                <div key={sv.id} className="whale-power-item">
                  <span><strong>{sv.name}</strong> · {sv.toolCount} 工具{sv.enabled ? '' : ` · ${t('已停用')}`}</span>
                  <div className="whale-power-item-actions">
                    <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageMcp(sv.id, 'refresh') }}>
                      {t('刷新')}
                    </button>
                    <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageMcp(sv.id, sv.enabled ? 'disable' : 'enable') }}>
                      {sv.enabled ? t('停用') : t('启用')}
                    </button>
                    <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageMcp(sv.id, 'remove') }}>
                      {t('移除')}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
          {extMsg && <p className="whale-power-feedback">{extMsg}</p>}
        </div>
      </details>

      <details className="whale-power-card">
        <summary className="whale-power-summary">
          <span className="whale-power-title">
            <Cpu size={15} aria-hidden="true" />
            <strong>{t('用量透视与记忆体检')}</strong>
          </span>
          <span className="whale-power-meta">
            <span className="whale-power-badge">{usageSummary}</span>
            <ChevronDown size={15} className="whale-power-chevron" aria-hidden="true" />
          </span>
        </summary>
        <div className="whale-power-body">
          {readiness && (
            <div className="whale-usage-pills" aria-label={t('能力就绪体检')}>
              {([
                ['modelKey', '主模型'],
                ['overlay', '悬浮窗'],
                ['accessibility', '无障碍'],
                ['notifications', '通知'],
                ['shizuku', 'Shizuku'],
                ['voiceKey', '语音'],
              ] as const).map(([k, lbl]) => (
                <span key={k} className={readiness[k] ? 'whale-ready-on' : 'whale-ready-off'}>
                  {readiness[k] ? '● ' : '○ '}{t(lbl)}
                </span>
              ))}
            </div>
          )}
          {usage && (
            <>
              <div className="whale-telemetry-grid" aria-label={t('核心用量遥测')}>
                <div className="whale-telemetry-cell">
                  <strong>{fmtTok(usage.chatTokens + (usage.petTokens ?? 0) + usage.workerTokens + (usage.bgTokens ?? 0) + usage.phoneTokens)}</strong>
                  <small>{t('累计 Token')}</small>
                </div>
                <div className="whale-telemetry-cell">
                  <strong>{Math.round(usage.cacheHitRatio * 100)}%</strong>
                  <small>{t('缓存命中')}</small>
                </div>
                <div className="whale-telemetry-cell">
                  <strong>{typeof usage.compressionRatio === 'number' && usage.compressionRatio > 0 ? `${Math.round(usage.compressionRatio * 100)}%` : '—'}</strong>
                  <small>{t('上下文压缩')}</small>
                </div>
              </div>
              <div className="whale-usage-pills">
                <span>{t('对话')} {fmtTok(usage.chatTokens)}</span>
                {typeof usage.petTokens === 'number' && usage.petTokens > 0 && <span>{t('桌宠')} {fmtTok(usage.petTokens)}</span>}
                <span>{t('后台')} {fmtTok(usage.workerTokens + (usage.bgTokens ?? 0))}</span>
                <span>{t('屏控')} {fmtTok(usage.phoneTokens)}</span>
              </div>
            </>
          )}
          <div className="whale-power-group">
            <label className="whale-power-label">{t('分场景模型（留空则自动路由）')}</label>
            <div className="whale-power-grid">
              <input value={fastModel} placeholder={t('日常闲聊快模型')} onChange={e => setFastModel(e.target.value)} />
              <input value={deepModel} placeholder={t('代码/长任务强模型')} onChange={e => setDeepModel(e.target.value)} />
            </div>
          </div>
          <div className="whale-power-actions">
            <button type="button" className="whale-perm-chip" onClick={() => { void saveRouter() }}>
              {t('保存路由')}
            </button>
            <button type="button" className="whale-perm-chip" onClick={() => { void compactMemory() }}>
              <Sparkles size={13} aria-hidden="true" />
              {t('一键记忆体检去重')}
            </button>
            <button
              type="button"
              className="whale-perm-chip"
              onClick={() => {
                const next = !nativeNotifOn
                setNativeNotifOn(next)
                window.dispatchEvent(new CustomEvent('whale:live-island-prefs', { detail: { nativeNotif: next } }))
                void runtimeBridge.companionDashboard({ operation: 'liveIslandPrefs', nativeNotif: next }).catch(() => {})
              }}
            >
              {nativeNotifOn ? '● ' : '○ '}{t('系统实况通知')}
            </button>
            <button
              type="button"
              className="whale-perm-chip"
              onClick={() => {
                const next = !floatingIslandOn
                setFloatingIslandOn(next)
                window.dispatchEvent(new CustomEvent('whale:live-island-prefs', { detail: { floatingIsland: next } }))
                void runtimeBridge.companionDashboard({ operation: 'liveIslandPrefs', floatingIsland: next }).catch(() => {})
              }}
            >
              {floatingIslandOn ? '● ' : '○ '}{t('自绘顶部岛')}
            </button>
          </div>
          {routerMsg && <p className="whale-power-feedback">{routerMsg}</p>}
          {memMsg && <p className="whale-power-feedback">{memMsg}</p>}
        </div>
      </details>
    </div>
  )
}
