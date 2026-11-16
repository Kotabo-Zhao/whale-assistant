import { describe, expect, it } from 'vitest'
import { canEnterConversation } from './conversationEntry'
import type { RuntimeSettings, RuntimeState } from '../platform/types'

const ready = { phase: 'ready', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 1, totalBytes: 1, runnerAvailable: true, installedVersion: '1' } as RuntimeState
const settings = { configuredModelProviders: ['deepseek'] } as RuntimeSettings

describe('直接进入会话', () => {
  it('凭据和运行环境都就绪时进入', () => {
    expect(canEnterConversation(ready, settings)).toBe(true)
    expect(canEnterConversation({ ...ready, phase: 'running' }, settings)).toBe(true)
  })

  it('缺凭据、待更新、未安装或失败时留在门屏', () => {
    expect(canEnterConversation(ready, { ...settings, configuredModelProviders: [] })).toBe(false)
    expect(canEnterConversation(ready, null)).toBe(false)
    expect(canEnterConversation({ ...ready, updateAvailable: true }, settings)).toBe(false)
    expect(canEnterConversation({ ...ready, phase: 'not-installed', installedVersion: undefined }, settings)).toBe(false)
    expect(canEnterConversation({ ...ready, phase: 'downloading' }, settings)).toBe(false)
    expect(canEnterConversation({ ...ready, phase: 'error', errorCode: 'FILESYSTEM_ERROR' }, settings)).toBe(false)
  })
})
