import test from 'node:test'
import assert from 'node:assert/strict'
import { routeTurnTools, planSuperagentTurn } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-tool-router.js'

const ALL_TOOLS = [
  'read', 'glob', 'grep', 'read_image', 'web_search', 'bash',
  'mobile_companion_capabilities', 'mobile_memory_search', 'mobile_memory_save',
  'mobile_knowledge_search', 'mobile_knowledge_save', 'mobile_secretary',
  'mobile_device_capabilities', 'mobile_device_battery', 'mobile_device_current_app',
  'mobile_screen_observe', 'mobile_screen_act', 'mobile_device_app_launch',
  'mobile_device_file_list', 'mobile_device_file_read', 'mobile_device_file_write',
  'mobile_superagent_read', 'mobile_worker_report', 'mobile_whale_feature',
]

test('日常闲聊仅挂载极简工具集，大幅裁剪无关工具', () => {
  const result = routeTurnTools('今天天气不错，给我讲个简短冷笑话', ALL_TOOLS)
  assert.equal(result.profile, 'chat')
  assert.ok(result.prunedCount >= 15, `expected heavy pruning, got ${result.prunedCount}`)
  assert.ok(result.allowedTools.includes('mobile_memory_search'))
  assert.ok(!result.allowedTools.includes('mobile_screen_act'))
})

test('电量等设备只读查询只挂载 device_read 工具，不携带屏控写操作', () => {
  const result = routeTurnTools('帮我看看手机现在还有多少电量', ALL_TOOLS)
  assert.equal(result.profile, 'device_read')
  assert.ok(result.allowedTools.includes('mobile_device_battery'))
  assert.ok(!result.allowedTools.includes('mobile_screen_act'))
})

test('复合意图自动合并所需域工具，不误裁', () => {
  const result = routeTurnTools('帮我读取本地 PDF 文件并记住里面的关键结论', ALL_TOOLS)
  assert.equal(result.profile, 'composite')
  assert.ok(result.allowedTools.includes('mobile_device_file_read'))
  assert.ok(result.allowedTools.includes('mobile_memory_save'))
})

test('涉及脚本、终端或强制全量时自动回落 full 不阻塞任务', () => {
  const result = routeTurnTools('帮我跑一个 python 脚本统计数据', ALL_TOOLS)
  assert.equal(result.profile, 'full')
  assert.equal(result.allowedTools.length, ALL_TOOLS.length)
  assert.equal(result.prunedCount, 0)
})

test('planSuperagentTurn：后台有在途 Worker 时用户发出暂停/纠偏自动触发高优抢占车道', () => {
  const plan = planSuperagentTurn('先停一下，别点了，换个方向查资料', ALL_TOOLS, { activeWorkerCount: 1, pendingReceiptCount: 2 })
  assert.equal(plan.executionLane, 'user_preempt')
  assert.equal(plan.userPriorityPreempt, true)
  assert.ok(plan.proactiveHints.some(h => h.includes('2 条后台任务')))
})

test('planSuperagentTurn：工坊创作与屏控自动分流至后台 Worker 车道不阻塞对话', () => {
  const createPlan = planSuperagentTurn('帮我创建一个每日习惯打卡小应用', ALL_TOOLS)
  assert.equal(createPlan.profile, 'workshop_create')
  assert.equal(createPlan.executionLane, 'background_worker')
  assert.equal(createPlan.recommendedWorkerMode, 'create')
  assert.ok(createPlan.allowedTools.includes('mobile_whale_feature'))

  const screenPlan = planSuperagentTurn('打开微信帮我看看最新消息', ALL_TOOLS)
  assert.equal(screenPlan.executionLane, 'background_worker')
  assert.equal(screenPlan.recommendedWorkerMode, 'device')
})
