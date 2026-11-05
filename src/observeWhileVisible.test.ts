import { afterEach, expect, it, vi } from 'vitest'
import { observeWhileVisible } from './observeWhileVisible'

const visibility = (state: 'hidden' | 'visible') => {
  vi.spyOn(document,'visibilityState','get').mockReturnValue(state)
  document.dispatchEvent(new Event('visibilitychange'))
}
afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

it('has no idle wakeups with a subscription and refreshes on events and return', async () => {
  vi.useFakeTimers(); visibility('visible')
  let changed!: () => void
  const remove = vi.fn()
  const read = vi.fn(async () => {})
  const stop = observeWhileVisible(listener => { changed = listener; return Promise.resolve({remove}) },read)
  await vi.advanceTimersByTimeAsync(0)
  expect(read).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledTimes(1)
  expect(vi.getTimerCount()).toBe(0)
  changed(); await vi.advanceTimersByTimeAsync(0)
  expect(read).toHaveBeenCalledTimes(2)
  visibility('hidden'); changed()
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledTimes(2)
  visibility('visible'); await vi.advanceTimersByTimeAsync(0)
  expect(read).toHaveBeenCalledTimes(3)
  stop(); await vi.advanceTimersByTimeAsync(0)
  changed(); expect(read).toHaveBeenCalledTimes(3); expect(remove).toHaveBeenCalledOnce()
})

it('coalesces changes during a slow read and rejects pre-hide responses', async () => {
  vi.useFakeTimers(); visibility('visible')
  let changed!: () => void
  let finish!: () => void
  const applied = vi.fn()
  const read = vi.fn(async (current: () => boolean) => {
    await new Promise<void>(resolve => { finish = resolve })
    if (current()) applied()
  })
  const stop = observeWhileVisible(listener => { changed = listener; return Promise.resolve({remove:vi.fn()}) },read)
  await vi.advanceTimersByTimeAsync(0)
  for (let i=0;i<20;i++) changed()
  visibility('hidden'); visibility('visible')
  expect(read).toHaveBeenCalledTimes(1)
  finish(); await vi.advanceTimersByTimeAsync(0)
  expect(applied).not.toHaveBeenCalled(); expect(read).toHaveBeenCalledTimes(2)
  stop(); finish(); await vi.advanceTimersByTimeAsync(0)
  expect(applied).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0)
})

it.each([undefined, () => Promise.reject(new Error('old bridge')), () => { throw new Error('unavailable') }])('falls back visibly when subscribing is unavailable', async subscribe => {
  vi.useFakeTimers(); visibility('visible')
  const read = vi.fn(async () => {})
  const stop = observeWhileVisible(subscribe,read)
  await vi.advanceTimersByTimeAsync(30000)
  expect(read).toHaveBeenCalledTimes(3)
  visibility('hidden'); await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledTimes(3); expect(vi.getTimerCount()).toBe(0)
  stop()
})

it('removes a subscription that resolves after unmount', async () => {
  vi.useFakeTimers()
  let finish!: (value: {remove: () => void}) => void
  const remove = vi.fn(); const read = vi.fn()
  const stop = observeWhileVisible(() => new Promise(resolve => { finish=resolve }),read)
  await Promise.resolve(); stop(); finish({remove})
  await vi.advanceTimersByTimeAsync(0)
  expect(remove).toHaveBeenCalledOnce(); expect(read).not.toHaveBeenCalled()
})
