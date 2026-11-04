import { describe, expect, it } from 'vitest'
import { primaryDestination, type AppView } from './appNavigation'

describe('主导航归属', () => {
  it.each<AppView>(['settings', 'settings-models', 'settings-runtime-keepalive', 'settings-diagnostics-log', 'terminal', 'environment', 'versions', 'files'])('%s 保持设置入口选中', view => {
    expect(primaryDestination(view)).toBe('settings')
  })
  it('会话管理返回助手区域，对话与插件保持独立', () => {
    expect(primaryDestination('sessions')).toBe('conversation')
    expect(primaryDestination('harness')).toBe('harness')
    expect(primaryDestination('plugins')).toBe('tools')
    expect(primaryDestination('tools')).toBe('tools')
    expect(primaryDestination('assistant-memory')).toBe('assistant-memory')
    expect(primaryDestination('assistant-tasks')).toBe('assistant-tasks')
  })
})
