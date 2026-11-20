import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { WhaleAutonomyCard } from './WhaleAutonomyCard'

import type { CompanionQuickSettings } from '../platform/types'

const mockGet = vi.fn<() => Promise<CompanionQuickSettings>>()
const mockSet = vi.fn<(field: keyof CompanionQuickSettings, value: boolean) => Promise<CompanionQuickSettings>>()

vi.mock('../platform/native', () => ({
  runtimeBridge: {
    getCompanionQuickSettings: () => mockGet(),
    setCompanionQuickSetting: (field: keyof CompanionQuickSettings, value: boolean) => mockSet(field, value),
  },
}))

const baseSnapshot = {
  memoryEnabled: true,
  bubbleMemoryAccess: true,
  notificationBubbles: false,
  candidateNotifications: false,
  notificationCapture: false,
  knowledgeModelAccess: true,
  knowledgeModelWrite: true,
  contextFollowup: true,
  notificationMemorySuggestions: false,
  automaticNotificationMemory: false,
  automaticUsageMemory: false,
  automaticAssistantMemory: true,
  reminders: false,
  quietHoursEnabled: false,
  masterPetFullAccess: true,
  masterMemoryAndRag: true,
  masterProactiveAndNotify: false,
}

describe('WhaleAutonomyCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    mockGet.mockResolvedValue(baseSnapshot)
    mockSet.mockImplementation((field: string, value: boolean) =>
      Promise.resolve({ ...baseSnapshot, [field]: value }),
    )
  })

  it('renders 3 master permission switches and syncs initial native state', async () => {
    render(<WhaleAutonomyCard />)
    const switches = await screen.findAllByRole('switch')
    expect(switches).toHaveLength(3)
    await waitFor(() => {
      expect((switches[0] as HTMLInputElement).checked).toBe(true)
      expect((switches[1] as HTMLInputElement).checked).toBe(true)
      expect((switches[2] as HTMLInputElement).checked).toBe(false)
    })
  })

  it('toggles a master switch and calls setCompanionQuickSetting once', async () => {
    render(<WhaleAutonomyCard />)
    const switches = await screen.findAllByRole('switch')
    fireEvent.click(switches[2])
    await waitFor(() => {
      expect(mockSet).toHaveBeenCalledWith('masterProactiveAndNotify', true)
    })
  })

  it('enables all 3 master switches when selecting fluent preset', async () => {
    render(<WhaleAutonomyCard />)
    const radios = screen.getAllByRole('radio')
    fireEvent.click(radios[0])
    await waitFor(() => {
      expect(mockSet).toHaveBeenCalledWith('masterPetFullAccess', true)
      expect(mockSet).toHaveBeenCalledWith('masterMemoryAndRag', true)
      expect(mockSet).toHaveBeenCalledWith('masterProactiveAndNotify', true)
    })
  })

  it('triggers system permission shortcut callbacks', () => {
    const onOverlay = vi.fn()
    const onA11y = vi.fn()
    const onNotify = vi.fn()
    render(
      <WhaleAutonomyCard
        onOpenOverlay={onOverlay}
        onOpenAccessibility={onA11y}
        onOpenNotifications={onNotify}
      />,
    )
    fireEvent.click(screen.getByText('悬浮桌宠权限'))
    fireEvent.click(screen.getByText('无障碍屏控'))
    fireEvent.click(screen.getByText('通知与提醒'))
    expect(onOverlay).toHaveBeenCalledTimes(1)
    expect(onA11y).toHaveBeenCalledTimes(1)
    expect(onNotify).toHaveBeenCalledTimes(1)
  })
})
