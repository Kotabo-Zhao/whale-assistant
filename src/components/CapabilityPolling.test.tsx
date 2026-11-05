import { act, cleanup, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { DeviceCapabilityCard } from './DeviceCapabilityCard'
import { RootEngineCard } from './RootEngineCard'
import type { RuntimeBridge } from '../platform/types'

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers() })

it.each([
  [DeviceCapabilityCard, 'getDeviceCapabilities'],
  [RootEngineCard, 'getRootEngineStatus'],
] as const)('%s suspends hidden wakeups and serializes slow automatic queries', async (Card, method) => {
  vi.useFakeTimers()
  let visible = true
  vi.spyOn(document, 'visibilityState', 'get').mockImplementation(() => visible ? 'visible' : 'hidden')
  let reject!: (error: Error) => void
  const read = vi.fn(() => new Promise((_, fail) => { reject = fail }))
  const view = render(<Card bridge={{ [method]: read } as unknown as RuntimeBridge} />)
  await act(async () => {
    window.dispatchEvent(new Event('focus'))
    await vi.advanceTimersByTimeAsync(30000)
  })
  expect(read).toHaveBeenCalledTimes(1)
  await act(async () => {
    visible = false
    document.dispatchEvent(new Event('visibilitychange'))
    reject(new Error('late response'))
    await Promise.resolve()
  })
  expect(vi.getTimerCount()).toBe(0)
  await act(async () => { await vi.advanceTimersByTimeAsync(60000) })
  expect(read).toHaveBeenCalledTimes(1)
  await act(async () => {
    visible = true
    document.dispatchEvent(new Event('visibilitychange'))
    await Promise.resolve()
  })
  expect(read).toHaveBeenCalledTimes(2)
  await act(async () => { reject(new Error('current response')); await Promise.resolve() })
  expect(vi.getTimerCount()).toBe(1)
  view.unmount()
  expect(vi.getTimerCount()).toBe(0)
})
