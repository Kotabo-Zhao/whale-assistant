/** Keep the user-facing agent available; the worker's existing permission guard still applies. */
export function foregroundWaitDenial({ name, arguments: input }) {
  const args = input ?? {}
  const note = '前台不能等待长任务。请交给 mobile_workers，说明接取状态后结束本轮；后台变化会自动唤醒协调。'
  if (name === 'sleep' || name === 'mobile_device_wait') return note
  if (name === 'bash' && (typeof args.command === 'string' && /(?:^|[;&|\s])(?:sleep|usleep)\s/u.test(args.command) ||
    args.run_in_background !== true && (typeof args.timeoutMs !== 'number' || args.timeoutMs > 5000))) return note
  if (name === 'job_output' && args.wait === true && (typeof args.timeout_ms !== 'number' || args.timeout_ms > 5000)) return note
}
