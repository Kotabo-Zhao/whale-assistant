type Subscription = { remove: () => Promise<void> | void }

/** Event-driven reads, with bounded fallback only when the bridge cannot subscribe. */
export function observeWhileVisible(
  subscribe: ((changed: () => void) => Promise<Subscription>) | undefined,
  read: (current: () => boolean) => Promise<void>,
): () => void {
  let stopped = false
  let running = false
  let dirty = false
  let generation = 0
  let fallback = false
  let timer: number | undefined
  let handle: Subscription | undefined
  const visible = () => !stopped && document.visibilityState !== 'hidden'
  const remove = (subscription: Subscription) => { void Promise.resolve().then(() => subscription.remove()).catch(() => {}) }
  const refresh = async () => {
    if (!visible()) return
    if (running) { dirty = true; return }
    window.clearTimeout(timer)
    dirty = false
    running = true
    const round = generation
    try { await read(() => visible() && generation === round) }
    finally {
      running = false
      if (visible()) {
        if (dirty) { dirty = false; void refresh() }
        else if (fallback) timer = window.setTimeout(() => { void refresh() },15000)
      }
    }
  }
  const changed = () => { void refresh() }
  const visibilityChanged = () => {
    generation++
    window.clearTimeout(timer)
    if (visible()) changed()
  }
  const failed = () => {
    if (stopped) return
    fallback = true
    changed()
  }
  document.addEventListener('visibilitychange',visibilityChanged)
  if (subscribe) {
    void Promise.resolve().then(() => subscribe(changed)).then(subscription => {
      if (stopped) remove(subscription)
      else { handle = subscription; changed() }
    },failed)
  } else failed()
  return () => {
    stopped = true
    window.clearTimeout(timer)
    document.removeEventListener('visibilitychange',visibilityChanged)
    if (handle) remove(handle)
  }
}
