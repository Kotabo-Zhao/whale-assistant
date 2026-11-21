import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'

const bridge = vi.hoisted(() => ({
  companionDashboard: vi.fn(),
  getCompanionQuickSettings: vi.fn(),
  setCompanionQuickSetting: vi.fn(),
  openCompanionSettings: vi.fn(),
}))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionSettingsButton } from './CompanionSettingsButton'

const defaults = {
  memoryEnabled: false,
  bubbleMemoryAccess: false,
  notificationBubbles: false,
  candidateNotifications: false,
  notificationCapture: false,
  knowledgeModelAccess: false,
  knowledgeModelWrite: false,
  contextFollowup: false,
  notificationMemorySuggestions: false,
  automaticNotificationMemory: false,
  automaticUsageMemory: false,
  automaticAssistantMemory: false,
  reminders: false,
  quietHoursEnabled: false,
  masterMemoryAndRag: false,
  masterPetFullAccess: false,
  masterProactiveAndNotify: false,
}

beforeEach(() => {
  saveLanguage('zh-CN')
  vi.clearAllMocks()
  bridge.companionDashboard.mockResolvedValue({ items: [], recurring: [] })
  bridge.getCompanionQuickSettings.mockResolvedValue(defaults)
  bridge.setCompanionQuickSetting.mockResolvedValue({ ...defaults, masterMemoryAndRag: true })
})

describe('助手设置（整合总开关）', () => {
  it('展示 4 个核心整合开关并通过原生总开关保存', async () => {
    render(<CompanionSettingsButton disabled={false} />)
    const switches = screen.getAllByRole('switch')
    expect(switches).toHaveLength(4)
    const toggle = screen.getByRole('switch', { name: /记忆与资料库/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setCompanionQuickSetting).toHaveBeenCalledWith('masterMemoryAndRag', true))
    await waitFor(() => expect(toggle).toBeChecked())
  })

  it('主动陪伴与通知感知开关通过 masterProactiveAndNotify 保存', async () => {
    bridge.setCompanionQuickSetting.mockResolvedValue({ ...defaults, masterProactiveAndNotify: true })
    render(<CompanionSettingsButton disabled={false} />)
    const toggle = screen.getByRole('switch', { name: /主动陪伴与通知感知/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setCompanionQuickSetting).toHaveBeenCalledWith('masterProactiveAndNotify', true))
  })

  it('点击系统权限与备份打开原生设置页', async () => {
    bridge.openCompanionSettings.mockResolvedValue(undefined)
    render(<CompanionSettingsButton disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '系统权限与备份' }))
    await waitFor(() => expect(bridge.openCompanionSettings).toHaveBeenCalledOnce())
  })
})
