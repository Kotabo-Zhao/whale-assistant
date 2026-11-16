import { hasConfiguredModelCredential } from '../modelProviders'
import type { RuntimeSettings, RuntimeState } from '../platform/types'

/** 就绪且凭据已在、没有待确认更新时，直接进入会话，不再停在「点击打开」。 */
export function canEnterConversation(runtime: RuntimeState, settings: RuntimeSettings | null): boolean {
  if (!hasConfiguredModelCredential(settings)) return false
  if (runtime.updateAvailable) return false
  if (runtime.phase === 'error') return false
  return runtime.phase === 'ready' || runtime.phase === 'running'
}
