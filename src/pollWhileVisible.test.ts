import { afterEach, expect, it, vi } from 'vitest'
import { pollWhileVisible } from './pollWhileVisible'

const visibility = (state: 'hidden' | 'visible') => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(state)
  document.dispatchEvent(new Event('visibilitychange'))
}

afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers() })

it('removes its wakeup while hidden and refreshes immediately on return', async () => {
  vi.useFakeTimers()
  visibility('hidden')
  const read = vi.fn(async () => {})
  const stop = pollWhileVisible(5000, read)
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).not.toHaveBeenCalled()
  visibility('visible')
  await vi.advanceTimersByTimeAsync(10000)
  expect(read).toHaveBeenCalledTimes(3)
  visibility('hidden')
  expect(vi.getTimerCount()).toBe(0)
  await vi.advanceTimersByTimeAsync(60000)
  expect(read).toHaveBeenCalledTimes(3)
  visibility('visible')
  expect(read).toHaveBeenCalledTimes(4)
  stop()
  await vi.advanceTimersByTimeAsync(10000)
  expect(vi.getTimerCount()).toBe(0)
})

it('does not overlap slow reads or apply a response from before the page was hidden', async () => {
  vi.useFakeTimers()
  visibility('visible')
  const applied = vi.fn()
  let finish!: () => void
  const read = vi.fn(async (current: () => boolean) => {
    await new Promise<void>(resolve => { finish = resolve })
    if (current()) applied()
  })
  const stop = pollWhileVisible(5000, read)
  visibility('hidden')
  visibility('visible')
  await vi.advanceTimersByTimeAsync(20000)
  expect(read).toHaveBeenCalledTimes(1)
  finish()
  await vi.advanceTimersByTimeAsync(0)
  expect(applied).not.toHaveBeenCalled()
  expect(read).toHaveBeenCalledTimes(2)
  stop()
  finish()
  await vi.advanceTimersByTimeAsync(10000)
  expect(applied).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
})
