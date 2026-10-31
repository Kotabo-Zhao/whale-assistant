import type { AutomationTaskState, AccessibilityServiceStatus } from './types'

/** 手机任务台账不能证明 Harness 已空闲；这里只负责阻止已知任务被停机打断。 */
export function pluginTaskBarrier(state: AutomationTaskState, serviceStatus?: AccessibilityServiceStatus): 'unknown' | 'interrupted' | 'active' | 'clear' {
  if (state.interrupted) return 'interrupted'
  if (state.task && !['done', 'failed', 'cancelled'].includes(state.task.status)) return 'active'
  if (!state.connected) return serviceStatus === 'off' && !state.task ? 'clear' : 'unknown'
  return 'clear'
}
