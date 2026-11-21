import { useEffect, useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings } from '../platform/types'
import './CompanionSettingsButton.css'

type MasterOption = {
  key: 'masterMemoryAndRag' | 'masterPetFullAccess' | 'masterProactiveAndNotify' | 'quietHoursEnabled'
  group: 'information' | 'notifications'
  title: string
  hint: string
}

const MASTER_OPTIONS: MasterOption[] = [
  {
    key: 'masterMemoryAndRag',
    group: 'information',
    title: '记忆与资料库',
    hint: '统一开启长期记忆、主人档案、资料库读写与自动偏好沉淀。',
  },
  {
    key: 'masterPetFullAccess',
    group: 'information',
    title: '桌宠全能力',
    hint: '允许桌宠与主界面共用全部原生工具、MCP/Skill 扩展与后台 Worker。',
  },
  {
    key: 'masterProactiveAndNotify',
    group: 'notifications',
    title: '主动陪伴与通知感知',
    hint: '整合通知摘要、日程感知、主动跟进与桌宠右上角圆点提示。',
  },
  {
    key: 'quietHoursEnabled',
    group: 'notifications',
    title: '免打扰模式',
    hint: '开启后静音主动打扰与桌宠提醒圆点。',
  },
]

function resolveChecked(settings: CompanionQuickSettings | null, key: MasterOption['key']): boolean {
  if (!settings) return false
  if (key === 'masterMemoryAndRag') {
    return settings.masterMemoryAndRag ?? Boolean(settings.memoryEnabled && settings.knowledgeModelAccess && settings.knowledgeModelWrite)
  }
  if (key === 'masterPetFullAccess') {
    return settings.masterPetFullAccess ?? Boolean(settings.bubbleMemoryAccess && settings.knowledgeModelAccess)
  }
  if (key === 'masterProactiveAndNotify') {
    return settings.masterProactiveAndNotify ?? Boolean(settings.notificationCapture && settings.contextFollowup)
  }
  return Boolean(settings.quietHoursEnabled)
}

export function CompanionSettingsButton({
  disabled,
  openMemory,
  section = 'all',
}: {
  disabled: boolean
  openMemory?: () => void
  section?: 'all' | 'information' | 'notifications'
}) {
  const [settings, setSettings] = useState<CompanionQuickSettings | null>(null)
  const [working, setWorking] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    let live = true
    void Promise.resolve()
      .then(() => runtimeBridge.getCompanionQuickSettings())
      .then(value => { if (live) setSettings(value) })
      .catch(() => { if (live) setMessage('无法读取助手设置，请在安卓应用中重试') })
    return () => { live = false }
  }, [])

  const update = async (field: MasterOption['key'], value: boolean) => {
    setWorking(true)
    setMessage('')
    try {
      setSettings(await runtimeBridge.setCompanionQuickSetting(field, value))
    } catch {
      setMessage('保存助手设置失败，请重试')
    } finally {
      setWorking(false)
    }
  }

  const visibleOptions = MASTER_OPTIONS.filter(opt => section === 'all' || opt.group === section)

  return (
    <section className="companion-settings" aria-label={t('助手偏好')}>
      {visibleOptions.map(option => (
        <label className="toggle-row" key={option.key}>
          <span>
            <strong>{t(option.title)}</strong>
            <small>{t(option.hint)}</small>
          </span>
          <input
            type="checkbox"
            role="switch"
            checked={resolveChecked(settings, option.key)}
            disabled={disabled || working || settings === null}
            onChange={event => { void update(option.key, event.target.checked) }}
          />
        </label>
      ))}
      <div className="companion-voice-actions">
        {openMemory && (
          <button className="button button-secondary" type="button" onClick={openMemory}>
            {t('打开记忆')}
          </button>
        )}
        <button
          className="button button-secondary"
          type="button"
          disabled={disabled || working}
          onClick={() => {
            setMessage('')
            void runtimeBridge.openCompanionSettings().catch(() => setMessage('无法打开助手设置，请在安卓应用中重试'))
          }}
        >
          {t('系统权限与备份')}
        </button>
      </div>
      {message && <p className="status-text-error" role="alert">{t(message)}</p>}
    </section>
  )
}
