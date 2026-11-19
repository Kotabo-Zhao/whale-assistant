import { useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings } from '../platform/types'
import {
  AUTONOMY_PRESETS,
  DEFAULT_AUTONOMY_PRESET,
  resolveAutonomyPreset,
  type AutonomyPresetId,
} from '../ui/whaleUiPreferences'

const STORAGE_KEY = 'whale.autonomyPreset'

const PRESET_NATIVE_FLAGS: Record<AutonomyPresetId, Array<[keyof CompanionQuickSettings, boolean]>> = {
  fluent: [
    ['memoryEnabled', true],
    ['bubbleMemoryAccess', true],
    ['knowledgeModelAccess', true],
    ['knowledgeModelWrite', true],
    ['automaticAssistantMemory', true],
    ['contextFollowup', true],
  ],
  balanced: [
    ['memoryEnabled', true],
    ['bubbleMemoryAccess', true],
    ['knowledgeModelAccess', true],
    ['knowledgeModelWrite', false],
    ['automaticAssistantMemory', false],
    ['contextFollowup', true],
  ],
  strict: [
    ['knowledgeModelWrite', false],
    ['automaticAssistantMemory', false],
    ['contextFollowup', false],
  ],
}

export function WhaleAutonomyCard() {
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
      // Non-Android / preview environments may not expose native bridge; keep local preset cleanly.
      setSyncState('error')
    }
  }

  return (
    <section className="whale-autonomy-card" aria-label={t('执行与权限模式')}>
      <div className="whale-autonomy-head">
        <strong>{t('执行与权限')}</strong>
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
    </section>
  )
}
