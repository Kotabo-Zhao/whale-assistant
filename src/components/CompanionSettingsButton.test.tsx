import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'

const bridge = vi.hoisted(() => ({
  companionDashboard: vi.fn(), getCompanionQuickSettings: vi.fn(), setCompanionQuickSetting: vi.fn(),
  openCompanionSettings: vi.fn(),
}))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionSettingsButton } from './CompanionSettingsButton'

const defaults = { memoryEnabled: false, bubbleMemoryAccess: false, notificationBubbles: true, candidateNotifications: false, notificationCapture: false, knowledgeModelAccess: false, knowledgeModelWrite: false, notificationMemorySuggestions: false, automaticNotificationMemory: false, automaticUsageMemory: false, automaticAssistantMemory: false, reminders: false, quietHoursEnabled: false }
beforeEach(() => {
  saveLanguage('zh-CN'); vi.clearAllMocks()
  bridge.companionDashboard.mockResolvedValue({ items: [], recurring: [] })
  bridge.getCompanionQuickSettings.mockResolvedValue(defaults)
  bridge.setCompanionQuickSetting.mockResolvedValue({ ...defaults, memoryEnabled: true })
})
describe('助手设置', () => {
  it('读取实际配置并通过原生接口保存', async () => {
    render(<CompanionSettingsButton disabled={false} />)
    const toggle = screen.getByRole('switch', { name: /允许使用长期记忆/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setCompanionQuickSetting).toHaveBeenCalledWith('memoryEnabled', true))
    await waitFor(() => expect(toggle).toBeChecked())
  })
  it('自动通知记忆需要单独开启，并通过原生设置保存', async () => {
    render(<CompanionSettingsButton disabled={false} />)
    const toggle = screen.getByRole('switch', { name: /自动记住其他应用通知/ })
    await waitFor(() => expect(toggle).toBeEnabled())
    expect(toggle).not.toBeChecked()
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setCompanionQuickSetting).toHaveBeenCalledWith('automaticNotificationMemory', true))
  })
  it('全部助手偏好打开原生分组目录', async () => {
    bridge.openCompanionSettings.mockResolvedValue(undefined)
    render(<CompanionSettingsButton disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: '全部助手偏好' }))
    await waitFor(() => expect(bridge.openCompanionSettings).toHaveBeenCalledOnce())
  })

})

it('总开关开启不冒充桌宠已授权，允许后保存独立访问开关', async () => {
  bridge.getCompanionQuickSettings.mockResolvedValue({ ...defaults, memoryEnabled: true })
  bridge.setCompanionQuickSetting.mockResolvedValue({ ...defaults, memoryEnabled: true, bubbleMemoryAccess: true })
  const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
  try {
    render(<CompanionSettingsButton disabled={false} />)
    await screen.findByText(/长期记忆已开启，但桌宠尚未获准访问/)
    const toggle = screen.getByRole('switch', { name: /允许桌宠访问长期记忆/ })
    fireEvent.click(toggle)
    expect(bridge.setCompanionQuickSetting).not.toHaveBeenCalled()
    fireEvent.click(toggle)
    await waitFor(() => expect(bridge.setCompanionQuickSetting).toHaveBeenCalledWith('bubbleMemoryAccess', true))
    await waitFor(() => expect(toggle).toBeChecked())
    expect(screen.queryByText(/长期记忆已开启，但桌宠尚未获准访问/)).toBeNull()
  } finally { confirm.mockRestore() }
})
