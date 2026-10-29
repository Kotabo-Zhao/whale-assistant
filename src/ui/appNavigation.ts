export type AppView =
  | 'conversation'
  | 'harness'
  | 'sessions'
  | 'settings'
  | 'settings-models'
  | 'settings-runtime'
  | 'settings-terminal'
  | 'settings-device-shell'
  | 'settings-diagnostics'
  | 'settings-automation'
  | 'settings-automation-accessibility'
  | 'settings-automation-subscreen'
  | 'settings-automation-params'
  // 三级页：与二级页共用同一个 SettingsScreen，只是 [settingsPageOf] 映射到各自的 SettingsPage。
  | 'settings-runtime-connection'
  | 'settings-runtime-keepalive'
  | 'settings-terminal-typography'
  | 'settings-terminal-background'
  | 'settings-terminal-dsh-background'
  | 'settings-diagnostics-log'
  | 'settings-diagnostics-capability'
  | 'settings-diagnostics-feedback'
  | 'terminal'
  | 'environment'
  | 'plugins'
  | 'versions'
  | 'files'

export type PrimaryDestination = 'conversation' | 'harness' | 'plugins' | 'settings'

/** 所有设置子页及工具页在两种导航中使用同一个归属。 */
export function primaryDestination(view: AppView): PrimaryDestination {
  if (view === 'conversation' || view === 'sessions') return 'conversation'
  if (view === 'harness' || view === 'plugins') return view
  return 'settings'
}
