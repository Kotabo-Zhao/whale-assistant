import { describe, expect, it } from 'vitest'
import {
  AUTONOMY_PRESETS,
  COMPACT_SETTINGS_GROUPS,
  DEFAULT_AUTONOMY_PRESET,
  resolveAutonomyPreset,
} from './whaleUiPreferences'

describe('whaleUiPreferences', () => {
  it('默认采用流畅模式并始终保留系统级安全底线', () => {
    const preset = resolveAutonomyPreset(undefined)
    expect(preset.id).toBe(DEFAULT_AUTONOMY_PRESET)
    expect(preset.autoAllowReadOnly).toBe(true)
    expect(preset.autoAllowWorkspaceWrite).toBe(true)
    expect(preset.preserveSystemSafetyGuards).toBe(true)
    for (const item of AUTONOMY_PRESETS) {
      expect(item.preserveSystemSafetyGuards).toBe(true)
    }
  })

  it('将设置收敛为 4 个清晰分组且高频组默认展开', () => {
    expect(COMPACT_SETTINGS_GROUPS).toHaveLength(4)
    expect(COMPACT_SETTINGS_GROUPS.filter(g => !g.collapsedByDefault).map(g => g.id)).toEqual([
      'model_appearance',
      'permissions_autonomy',
    ])
  })
})
