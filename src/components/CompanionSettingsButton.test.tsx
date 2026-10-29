import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { saveLanguage } from '../i18n'

const bridge = vi.hoisted(() => ({
  companionDashboard: vi.fn(), getCompanionQuickSettings: vi.fn(), setCompanionQuickSetting: vi.fn(),
  runHarnessAssistantTool: vi.fn(), openCompanionSettings: vi.fn(),
}))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionSettingsButton } from './CompanionSettingsButton'

const defaults = { memoryEnabled: false, notificationBubbles: true, candidateNotifications: false, notificationCapture: false, knowledgeModelAccess: false, notificationMemorySuggestions: false, automaticNotificationMemory: false, automaticUsageMemory: false, reminders: false, quietHoursEnabled: false }
beforeEach(() => {
  saveLanguage('zh-CN'); vi.clearAllMocks()
  bridge.companionDashboard.mockResolvedValue({ items: [], recurring: [] })
  bridge.getCompanionQuickSettings.mockResolvedValue(defaults)
  bridge.setCompanionQuickSetting.mockResolvedValue({ ...defaults, memoryEnabled: true })
  bridge.runHarnessAssistantTool.mockResolvedValue(undefined)
})
describe('助手设置与语音入口', () => {
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
  it('语音和朗读走当前对话，未就绪时显示提示', async () => {
    render(<CompanionSettingsButton disabled={false} />)
    await waitFor(() => expect(bridge.getCompanionQuickSettings).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('button', { name: '语音输入' }))
    await waitFor(() => expect(bridge.runHarnessAssistantTool).toHaveBeenCalledWith('voice'))
    await waitFor(() => expect(screen.getByRole('button', { name: '朗读当前回复' })).toBeEnabled())
    bridge.runHarnessAssistantTool.mockRejectedValueOnce(new Error('not ready'))
    fireEvent.click(screen.getByRole('button', { name: '朗读当前回复' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('请先打开对话标签')
  })
})
