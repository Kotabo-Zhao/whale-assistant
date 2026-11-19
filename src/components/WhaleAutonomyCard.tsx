import { useState } from 'react'
import { t } from '../i18n'
import {
  AUTONOMY_PRESETS,
  DEFAULT_AUTONOMY_PRESET,
  resolveAutonomyPreset,
  type AutonomyPresetId,
} from '../ui/whaleUiPreferences'

const STORAGE_KEY = 'whale.autonomyPreset'

export function WhaleAutonomyCard() {
  const [autonomyId, setAutonomyId] = useState<AutonomyPresetId>(() => {
    try {
      return resolveAutonomyPreset(window.localStorage.getItem(STORAGE_KEY)).id
    } catch {
      return DEFAULT_AUTONOMY_PRESET
    }
  })

  const activeAutonomy = resolveAutonomyPreset(autonomyId)

  const selectAutonomy = (id: AutonomyPresetId): void => {
    setAutonomyId(id)
    try {
      window.localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // Ignore private-mode storage errors.
    }
  }

  return (
    <section className="whale-autonomy-card" aria-label={t('执行与权限模式')}>
      <div className="whale-autonomy-head">
        <strong>{t('执行与权限')}</strong>
        <span>{t(activeAutonomy.label)}</span>
      </div>
      <div className="whale-autonomy-pills" role="radiogroup" aria-label={t('自治模式')}>
        {AUTONOMY_PRESETS.map(preset => (
          <button
            key={preset.id}
            type="button"
            role="radio"
            aria-checked={autonomyId === preset.id}
            className={'whale-autonomy-pill' + (autonomyId === preset.id ? ' is-active' : '')}
            onClick={() => selectAutonomy(preset.id)}
          >
            {t(preset.label)}
          </button>
        ))}
      </div>
      <p className="whale-autonomy-desc">{t(activeAutonomy.description)}</p>
    </section>
  )
}
