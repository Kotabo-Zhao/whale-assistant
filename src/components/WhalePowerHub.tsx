import { useCallback, useEffect, useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'

type SkillRow = { slug: string; name: string; description: string; enabled: boolean; sourceUrl?: string }
type McpRow = { id: string; name: string; url: string; enabled: boolean; toolCount: number; tools?: string[] }
type UsageBreakdown = {
  totalTokens: number
  cacheHitRatio: number
  estimatedYuan: number
  chatTokens: number
  workerTokens: number
  phoneTokens: number
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
      setMemMsg(removed > 0 ? `体检完成：扫描 ${scanned} 条，已清理 ${removed} 条重复/空记忆` : `体检完成：扫描 ${scanned} 条，记忆库非常干净`)
    } catch {
      setMemMsg('记忆体检失败，请在安卓应用中重试')
    }
  }

  return (
    <div className="whale-voice-box" aria-label={t('扩展、路由与记忆体检')}>
      <details>
        <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem' }}>
          {t('MCP 与 Skill 扩展管理')}（{skills.length} Skill · {mcpServers.length} MCP）
        </summary>
        <div style={{ display: 'grid', gap: 8, marginTop: 10 }}>
          <div className="whale-voice-row">
            <input
              value={skillUrl}
              placeholder={t('粘贴 GitHub 或 https://.../SKILL.md 地址')}
              onChange={e => setSkillUrl(e.target.value)}
            />
            <button type="button" className="whale-perm-chip" disabled={extBusy || !skillUrl.trim()} onClick={() => { void installSkill() }}>
              {t('装 Skill')}
            </button>
          </div>
          {skills.map(sk => (
            <div key={sk.slug} className="whale-voice-row" style={{ justifyContent: 'space-between' }}>
              <span style={{ fontSize: '0.8rem', flex: 1 }}>
                <strong>{sk.name}</strong> {sk.enabled ? '' : '（已停用）'}
              </span>
              <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageSkill(sk.slug, sk.enabled ? 'disable' : 'enable') }}>
                {sk.enabled ? t('停用') : t('启用')}
              </button>
              <button type="button" className="whale-perm-chip" disabled={extBusy} onClick={() => { void manageSkill(sk.slug, 'delete') }}>
                {t('删除')}
              </button>
            </div>
          ))}
          <div className="whale-voice-row">
            <input value={mcpName} placeholder={t('MCP 名称')} onChange={e => setMcpName(e.target.value)} />
            <input value={mcpUrl} placeholder={t('MCP 地址 (https://...)')} onChange={e => setMcpUrl(e.target.value)} />
          </div>
          <div className="whale-voice-row">
            <input
              type="password"
              value={mcpAuth}
              placeholder={t('可选 Authorization（如 Bearer xxx，本地加密不进聊天）')}
              onChange={e => setMcpAuth(e.target.value)}
            />
            <button type="button" className="whale-perm-chip" disabled={extBusy || !mcpUrl.trim()} onClick={() => { void addMcp() }}>
              {t('接 MCP')}
            </button>
          </div>
          {mcpServers.map(sv => (
            <div key={sv.id} className="whale-voice-row" style={{ justifyContent: 'space-between' }}>
              <span style={{ fontSize: '0.8rem', flex: 1 }}>
                <strong>{sv.name}</strong> · {sv.toolCount} 工具{sv.enabled ? '' : '（已停用）'}
              </span>
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
          ))}
          {extMsg && <small style={{ color: 'var(--blue)' }}>{extMsg}</small>}
        </div>
      </details>

      <details style={{ marginTop: 10 }}>
        <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '0.88rem' }}>
          {t('今日消耗透视与分场景模型')}
          {usage ? `（${fmtTok(usage.totalTokens)} · ¥${usage.estimatedYuan.toFixed(2)}）` : ''}
        </summary>
        <div style={{ display: 'grid', gap: 8, marginTop: 10 }}>
          {usage && (
            <small style={{ color: 'var(--muted)' }}>
              对话 {fmtTok(usage.chatTokens)} · 后台任务 {fmtTok(usage.workerTokens)} · 屏控 {fmtTok(usage.phoneTokens)} · 缓存命中 {Math.round(usage.cacheHitRatio * 100)}%
            </small>
          )}
          <div className="whale-voice-row">
            <input value={fastModel} placeholder={t('日常闲聊快模型（留空自动）')} onChange={e => setFastModel(e.target.value)} />
            <input value={deepModel} placeholder={t('代码/长任务强模型（留空自动）')} onChange={e => setDeepModel(e.target.value)} />
          </div>
          <div className="whale-voice-row">
            <button type="button" className="whale-perm-chip" onClick={() => { void saveRouter() }}>{t('保存分场景模型')}</button>
            <button type="button" className="whale-perm-chip" onClick={() => { void compactMemory() }}>{t('一键记忆体检去重')}</button>
          </div>
          {routerMsg && <small style={{ color: 'var(--blue)' }}>{routerMsg}</small>}
          {memMsg && <small style={{ color: 'var(--blue)' }}>{memMsg}</small>}
        </div>
      </details>
    </div>
  )
}
