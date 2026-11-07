/** A request owns its iterator. A stalled iterator cannot write into a later turn. */
export function mobileQueueRequest(parent, hasPending, { idleMs = 30000, maxMs = 180000, pollMs = 250 } = {}) {
  const controller = new AbortController()
  const cancel = () => controller.abort(parent.reason)
  parent.addEventListener('abort', cancel, { once: true })
  if (parent.aborted) cancel()
  return {
    signal: controller.signal,
    async * stream(source) {
      const iterator = source[Symbol.asyncIterator]()
      let progress = Date.now()
      let complete = false
      let timer
      let onAbort
      const stopped = new Promise((_, reject) => {
        onAbort = () => reject(controller.signal.reason instanceof Error ? controller.signal.reason : new Error('回复已停止'))
        controller.signal.addEventListener('abort', onAbort, { once: true })
        if (controller.signal.aborted) onAbort()
        timer = setInterval(() => {
          if (Date.now() - progress >= (hasPending() ? idleMs : maxMs)) {
            controller.abort(new Error('回复长时间没有进展，原回合已结束，后续消息继续处理。'))
          }
        }, pollMs)
      })
      try {
        while (true) {
          const next = await Promise.race([iterator.next(), stopped])
          controller.signal.throwIfAborted()
          if (next.done) { complete = true; return }
          if (!['heartbeat', 'ping', 'usage'].includes(next.value?.type)) progress = Date.now()
          yield next.value
        }
      } finally {
        clearInterval(timer)
        parent.removeEventListener('abort', cancel)
        controller.signal.removeEventListener('abort', onAbort)
        if (!complete) Promise.resolve(iterator.return?.()).catch(() => {})
      }
    },
  }
}
