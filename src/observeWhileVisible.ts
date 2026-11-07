type Subscription = { remove: () => Promise<void> | void }

/** Refresh on changes and retry failed reads while the page is visible. */
export function observeWhileVisible(
  subscribe: ((changed: () => void) => Promise<Subscription>) | undefined,
  read: (current: () => boolean) => Promise<void | false>,
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
    let retry = false
    try { retry = await read(() => visible() && generation === round) === false }
    finally {
      running = false
      if (visible()) {
        if (dirty) { dirty = false; void refresh() }
        else if (retry || fallback) timer = window.setTimeout(changed, retry ? 8000 : 15000)
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
