/** Schedule the next read only after the previous one settles and the page is visible. */
export function pollWhileVisible(delay: number, read: (current: () => boolean) => Promise<void>, refreshOnFocus = false): () => void {
  let timer: number | undefined
  let running = false
  let stopped = false
  let generation = 0
  const visible = () => !stopped && document.visibilityState !== 'hidden'
  const refresh = async () => {
    if (running || !visible()) return
    running = true
    const round = generation
    try {
      await read(() => visible() && generation === round)
    } finally {
      running = false
      if (visible()) {
        if (round !== generation) void refresh()
        else timer = window.setTimeout(() => { void refresh() }, delay)
      }
    }
  }
  const changed = () => {
    generation++
    window.clearTimeout(timer)
    void refresh()
  }
  document.addEventListener('visibilitychange', changed)
  if (refreshOnFocus) window.addEventListener('focus', changed)
  void refresh()
  return () => {
    stopped = true
    window.clearTimeout(timer)
    document.removeEventListener('visibilitychange', changed)
    if (refreshOnFocus) window.removeEventListener('focus', changed)
  }
}
