import { useState } from 'react'
import { Bell, Accessibility, Sparkles } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings } from '../platform/types'
import {
  AUTONOMY_PRESETS,
  DEFAULT_AUTONOMY_PRESET,
  resolveAutonomyPreset,
  type AutonomyPresetId,
} from '../ui/whaleUiPreferences'

const STORAGE_KEY = 'whale.autonomyPreset.v1'

const PRESET_NATIVE_FLAGS: Record<AutonomyPresetId, Array<[keyof CompanionQuickSettings, boolean]>> = {
  fluent: [
    ['memoryEnabled', true],
    ['bubbleMemoryAccess', true],
    ['notificationCapture', true],
    ['knowledgeModelAccess', true],
    ['knowledgeModelWrite', true],
    ['automaticAssistantMemory', true],
    ['automaticNotificationMemory', true],
    ['automaticUsageMemory', true],
    ['notificationBubbles', true],
    ['candidateNotifications', true],
    ['contextFollowup', true],
    ['reminders', true],
  ],
  balanced: [
    ['memoryEnabled', true],
    ['bubbleMemoryAccess', true],
    ['notificationCapture', true],
    ['knowledgeModelAccess', true],
    ['knowledgeModelWrite', false],
    ['automaticAssistantMemory', false],
    ['automaticNotificationMemory', false],
    ['automaticUsageMemory', false],
    ['notificationBubbles', true],
    ['candidateNotifications', false],
    ['contextFollowup', true],
    ['reminders', true],
  ],
  strict: [
    ['notificationCapture', false],
    ['knowledgeModelWrite', false],
    ['automaticAssistantMemory', false],
    ['automaticNotificationMemory', false],
    ['automaticUsageMemory', false],
    ['notificationBubbles', false],
    ['candidateNotifications', false],
    ['contextFollowup', false],
  ],
}

export interface WhaleAutonomyCardProps {
  onOpenOverlay?: () => void
  onOpenAccessibility?: () => void
  onOpenNotifications?: () => void
}

export function WhaleAutonomyCard({
  onOpenOverlay,
  onOpenAccessibility,
  onOpenNotifications,
}: WhaleAutonomyCardProps) {
  const [autonomyId, setAutonomyId] = useState<AutonomyPresetId>(() => {
    try {
      return resolveAutonomyPreset(window.localStorage.getItem(STORAGE_KEY)).id
    } catch {
      return DEFAULT_AUTONOMY_PRESET
    }
  })
  const [syncState, setSyncState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')

  const activeAutonomy = resolveAutonomyPreset(autonomyId)

  const selectAutonomy = async (id: AutonomyPresetId): Promise<void> => {
    setAutonomyId(id)
    setSyncState('saving')
    try {
      window.localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // Ignore private-mode storage errors.
    }
    try {
      const updates = PRESET_NATIVE_FLAGS[id]
      for (const [field, value] of updates) {
        await runtimeBridge.setCompanionQuickSetting(field, value)
      }
      setSyncState('saved')
    } catch {
      setSyncState('error')
    }
  }

  return (
    <section className="whale-autonomy-card" aria-label={t('执行与权限模式')}>
      <div className="whale-autonomy-head">
        <strong>{t('一站式权限与自治')}</strong>
        <span>
          {syncState === 'saving'
            ? t('同步中…')
            : syncState === 'saved'
              ? `${t(activeAutonomy.label)} · ${t('已生效')}`
              : t(activeAutonomy.label)}
        </span>
      </div>
      <div className="whale-autonomy-pills" role="radiogroup" aria-label={t('自治模式')}>
        {AUTONOMY_PRESETS.map(preset => (
          <button
            key={preset.id}
            type="button"
            role="radio"
            aria-checked={autonomyId === preset.id}
            disabled={syncState === 'saving'}
            className={'whale-autonomy-pill' + (autonomyId === preset.id ? ' is-active' : '')}
            onClick={() => { void selectAutonomy(preset.id) }}
          >
            {t(preset.label)}
          </button>
        ))}
      </div>
      <p className="whale-autonomy-desc">{t(activeAutonomy.description)}</p>
      {(onOpenOverlay || onOpenAccessibility || onOpenNotifications) && (
        <div className="whale-perm-shortcuts" aria-label={t('系统权限快捷授权')}>
          {onOpenOverlay && (
            <button type="button" className="whale-perm-chip" onClick={onOpenOverlay}>
              <Sparkles size={13} aria-hidden="true" />
              {t('悬浮桌宠权限')}
            </button>
          )}
          {onOpenAccessibility && (
            <button type="button" className="whale-perm-chip" onClick={onOpenAccessibility}>
              <Accessibility size={13} aria-hidden="true" />
              {t('无障碍屏控')}
            </button>
          )}
          {onOpenNotifications && (
            <button type="button" className="whale-perm-chip" onClick={onOpenNotifications}>
              <Bell size={13} aria-hidden="true" />
              {t('通知与提醒')}
            </button>
          )}
        </div>
      )}
    </section>
  )
}
