import { expect, it } from 'vitest'
import { pluginTaskBarrier } from './pluginMaintenance'
import type { AutomationTaskState } from './types'
it('断开服务不是空闲，中断记录优先阻止维护', () => {
 expect(pluginTaskBarrier({connected:false,task:null})).toBe('unknown')
 expect(pluginTaskBarrier({connected:false,task:null,interrupted:true})).toBe('interrupted')
 expect(pluginTaskBarrier({connected:true,task:null})).toBe('clear')
})
it.each(['planned','running','blocked'] as const)('任务 %s 不能被插件维护中断', status => {
 expect(pluginTaskBarrier({connected:true,task:{status} } as AutomationTaskState)).toBe('active')
})

it('系统服务明确关闭且没有中断记录时允许维护，不要求用户开启服务', () => {
 expect(pluginTaskBarrier({connected:false,task:null}, 'off')).toBe('clear')
 expect(pluginTaskBarrier({connected:false,task:null}, 'enabled_disconnected')).toBe('unknown')
 expect(pluginTaskBarrier({connected:false,task:null,interrupted:true}, 'off')).toBe('interrupted')
})
