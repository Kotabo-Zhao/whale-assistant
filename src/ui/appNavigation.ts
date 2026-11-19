export type AppView =
  | 'conversation'
  | 'whale-chat'
  | 'harness'
  | 'assistant-memory'
  | 'assistant-tasks'
  | 'assistant-persona'
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
  | 'tools'
  | 'plugins'
  | 'versions'
  | 'files'

export type PrimaryDestination = 'conversation' | 'harness' | 'assistant-memory' | 'assistant-tasks' | 'tools' | 'settings'

/** 所有设置子页及工具页在两种导航中使用同一个归属。 */
export function primaryDestination(view: AppView): PrimaryDestination {
  if (view === 'conversation' || view === 'whale-chat' || view === 'sessions') return 'conversation'
  if (view === 'assistant-memory' || view === 'assistant-tasks') return view
  if (view === 'harness') return view
  if (view === 'tools' || view === 'plugins') return 'tools'
  return 'settings'
}
