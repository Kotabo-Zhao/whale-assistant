import { useCallback, useEffect, useState } from 'react'
import { CheckCircle2, RefreshCw, ShieldAlert, Wrench } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/runtimeBridge'

interface HealthSnapshot {
  shizuku?: { running?: boolean; authorized?: boolean; ready?: boolean }
  accessibility?: { systemEnabled?: boolean; connected?: boolean; zombie?: boolean; whitelistEnabled?: boolean; whitelistCount?: number; ready?: boolean }
  virtualScreen?: { running?: boolean; ready?: boolean }
}

/**
 * 自动化设置页顶部「一键检测与自愈」卡片：
 * 统一展示 Shizuku / 无障碍（含僵尸断连检测）/ 副屏三路状态，一键通过 Shizuku 自动激活/重连无障碍并放行空拦截白名单。
 */
export function AutomationQuickHealCard({ onOpenAccessibilitySettings }: { onOpenAccessibilitySettings: () => void }) {
  const [health, setHealth] = useState<HealthSnapshot | null>(null)
  const [busy, setBusy] = useState(false)
  const [report, setReport] = useState<string>('')
  const [actions, setActions] = useState<string[]>([])

  const refresh = useCallback(async () => {
    try {
      const res = await runtimeBridge.companionDashboard?.({ operation: 'automationHealth' }) as HealthSnapshot | undefined
      if (res) setHealth(res)
    } catch {
      // Ignore outside native shell
    }
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const handleAutoHeal = async () => {
    setBusy(true)
    setReport('')
    setActions([])
    try {
      const res = await runtimeBridge.companionDashboard?.({
        operation: 'automationAutoFix',
        relaxWhitelist: true,
      }) as { actions?: unknown[]; nextStep?: string; health?: HealthSnapshot } | undefined
      if (res?.health) setHealth(res.health)
      if (Array.isArray(res?.actions)) {
        setActions(res.actions.filter((x): x is string => typeof x === 'string'))
      }
      if (typeof res?.nextStep === 'string') {
        setReport(res.nextStep)
      }
    } catch (err) {
      setReport(err instanceof Error ? err.message : t('自动修复失败，请检查 Shizuku 状态。'))
    } finally {
      setBusy(false)
    }
  }

  const shizukuReady = Boolean(health?.shizuku?.ready)
  const a11yReady = Boolean(health?.accessibility?.ready)
  const a11yZombie = Boolean(health?.accessibility?.zombie)
  const vsReady = Boolean(health?.virtualScreen?.ready)

  return (
    <div className="settings-subsection" aria-labelledby="automation-quick-heal">
      <div className="section-title section-title-action">
        <span className="section-icon"><Wrench size={19} /></span>
        <div>
          <h3 id="automation-quick-heal">{t('一键诊断与自愈（无障碍 + 副屏）')}</h3>
          <p>{t('Shizuku 已运行时可免进系统设置自动激活/重连僵尸无障碍，并自动放行空的拦截白名单。')}</p>
        </div>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, margin: '10px 0' }}>
        <span className={`status-chip ${shizukuReady ? 'success' : ''}`}>
          {shizukuReady ? <CheckCircle2 size={13} /> : <ShieldAlert size={13} />}
          {shizukuReady ? t('Shizuku 已就绪') : t('Shizuku 未就绪')}
        </span>
        <span className={`status-chip ${a11yReady ? 'success' : ''}`}>
          {a11yReady ? <CheckCircle2 size={13} /> : <ShieldAlert size={13} />}
          {a11yReady ? t('无障碍已生效') : (a11yZombie ? t('无障碍假死（可一键重连）') : t('无障碍未开启'))}
        </span>
        <span className={`status-chip ${vsReady ? 'success' : ''}`}>
          {vsReady ? <CheckCircle2 size={13} /> : <ShieldAlert size={13} />}
          {health?.virtualScreen?.running ? t('副屏运行中') : (vsReady ? t('副屏随时可启') : t('副屏待 Shizuku'))}
        </span>
      </div>

      <div className="action-row" style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        <button type="button" className="button button-primary" disabled={busy} onClick={() => { void handleAutoHeal() }}>
          <RefreshCw size={16} />
          {busy ? t('正在自检修复…') : t('一键自动修复')}
        </button>
        {!a11yReady && (
          <button type="button" className="button button-secondary" disabled={busy} onClick={onOpenAccessibilitySettings}>
            {t('打开系统无障碍')}
          </button>
        )}
      </div>

      {actions.length > 0 && (
        <ul style={{ margin: '10px 0 4px', paddingLeft: 18, fontSize: '0.84rem', lineHeight: 1.5 }}>
          {actions.map((item, idx) => <li key={idx}>{item}</li>)}
        </ul>
      )}
      {report && <p className="settings-note" style={{ marginTop: 6 }}>{report}</p>}
    </div>
  )
}
