/**
 * Whale Super Agent 意图路由、最小工具集裁剪与 Personal Agent 车道规划器。
 *
 * 解决主会话首轮携带全量工具定义导致约 3 万 token 膨胀、响应慢与功耗高的问题；
 * 同时实现“前台用户追问优先抢占 + 复杂任务后台 Worker 自动分流 + 主动承接提示”，
 * 让手机端助手具备连贯、轻快、主动的 Personal Super Agent 体验。
 */

export const INTENT_PROFILES = Object.freeze({
  chat: {
    id: 'chat',
    targetFirstTokenMs: 1500,
    maxModelTurns: 2,
    tools: [
      'mobile_companion_capabilities',
      'mobile_memory_search',
      'mobile_memory_save',
      'mobile_secretary',
      'mobile_worker_report',
    ],
  },
  memory_knowledge: {
    id: 'memory_knowledge',
    targetFirstTokenMs: 2000,
    maxModelTurns: 3,
    tools: [
      'mobile_companion_capabilities',
      'mobile_context_recall',
      'mobile_memory_search',
      'mobile_memory_hybrid_search',
      'mobile_memory_save',
      'mobile_memory_update',
      'mobile_knowledge_search',
      'mobile_knowledge_hybrid_search',
      'mobile_knowledge_recent',
      'mobile_knowledge_read',
      'mobile_knowledge_save',
      'mobile_knowledge_update',
      'mobile_knowledge_status',
      'mobile_owner_profile',
      'mobile_secretary',
      'mobile_task_search',
    ],
  },
  device_read: {
    id: 'device_read',
    targetFirstTokenMs: 1800,
    maxModelTurns: 2,
    tools: [
      'mobile_device_capabilities',
      'mobile_device_info',
      'mobile_device_battery',
      'mobile_device_current_app',
      'mobile_device_list_packages',
      'mobile_device_get_setting',
      'mobile_device_background_tasks',
    ],
  },
  device_screen: {
    id: 'device_screen',
    targetFirstTokenMs: 2500,
    maxModelTurns: 6,
    tools: [
      'mobile_device_capabilities',
      'mobile_device_current_app',
      'mobile_device_list_packages',
      'mobile_device_app_launch',
      'mobile_screen_observe',
      'mobile_screen_act',
      'mobile_screen_sequence',
      'mobile_screen_recover',
      'mobile_device_wait',
      'mobile_accessibility_tree',
      'mobile_accessibility_action',
      'mobile_accessibility_back',
      'mobile_virtual_screen_state',
      'mobile_virtual_screen_screenshot',
      'mobile_virtual_screen_action',
      'mobile_virtual_screen_target',
      'mobile_worker_report',
    ],
  },
  workspace_file: {
    id: 'workspace_file',
    targetFirstTokenMs: 2200,
    maxModelTurns: 4,
    tools: [
      'read',
      'glob',
      'grep',
      'read_image',
      'mobile_device_file_list',
      'mobile_device_file_read',
      'mobile_device_file_write',
      'mobile_device_file_download',
      'mobile_device_file_upload',
      'mobile_device_file_mkdir',
      'mobile_knowledge_save',
      'mobile_worker_report',
    ],
  },
  superagent_manage: {
    id: 'superagent_manage',
    targetFirstTokenMs: 2000,
    maxModelTurns: 3,
    tools: [
      'mobile_superagent_read',
      'mobile_worker_report',
      'mobile_task_search',
      'mobile_secretary',
      'mobile_device_background_tasks',
    ],
  },
  workshop_create: {
    id: 'workshop_create',
    targetFirstTokenMs: 2200,
    maxModelTurns: 4,
    tools: [
      'mobile_whale_feature',
      'mobile_worker_report',
      'mobile_knowledge_save',
    ],
  },
})

const SIGNALS = [
  {
    profile: 'workshop_create',
    pattern: /(创建.{0,8}(功能|工具|小应用|插件|打卡|计时器|记账)|做个.{0,8}(工具|小应用|页面|计算器)|功能工坊|whale_feature)/i,
  },
  {
    profile: 'device_screen',
    pattern: /(打开|启动|点击|滑动|操作|帮我点|截屏|截图|副屏|无障碍|去.{1,8}(app|应用|软件|微信|淘宝|抖音|美团|设置)|screen|tap|swipe|click|launch\s+app)/i,
  },
  {
    profile: 'workspace_file',
    pattern: /(文件|文档|目录|文件夹|导出|导入|inbox|outbox|workspace|pdf|docx|xlsx|csv|读取本地|整理文件|写入文件)/i,
  },
  {
    profile: 'device_read',
    pattern: /(电量|电池|手机信息|当前应用|装了哪些|已安装应用|系统版本|后台任务|battery|device\s+info|installed\s+apps)/i,
  },
  {
    profile: 'superagent_manage',
    pattern: /(工单|后台进度|任务状态|worker|superagent|暂停任务|恢复任务|取消任务|看门狗|watchdog)/i,
  },
  {
    profile: 'memory_knowledge',
    pattern: /(记住|记忆|备忘|提醒|日程|事项|资料库|笔记|知识库|档案|总结一下|我之前|上次说的|memory|remind|note|knowledge)/i,
  },
]

const PREEMPT_PATTERN = /(暂停|停止|别做了|先停下|取消|改一下|等等|换个方向|stop|pause|cancel|abort)/i
const HEAVY_WORK_PATTERN = /(批量|整理所有|深入研究|多步|帮我持续|监控|定时|每天|汇总全部|依次打开|自动化)/i

/**
 * 根据用户输入与选项，决定 Router 本轮应加载的意图档位与最小工具集。
 */
export function routeTurnTools(inputText, availableTools = [], options = {}) {
  const text = String(inputText ?? '').trim()
  const allNames = availableTools.map(item => (typeof item === 'string' ? item : item?.name)).filter(Boolean)

  if (options.forceFull || /(bash|shell|terminal|python|node|脚本|终端|编译|运行命令|写代码|插件)/i.test(text)) {
    return {
      profile: 'full',
      matchedProfiles: ['full'],
      allowedTools: allNames,
      prunedCount: 0,
      targetFirstTokenMs: 3000,
      maxModelTurns: 8,
    }
  }

  const matched = []
  for (const signal of SIGNALS) {
    if (signal.pattern.test(text)) matched.push(signal.profile)
  }

  const activeProfiles = matched.length > 0 ? matched : ['chat']
  const allowSet = new Set()
  let targetFirstTokenMs = 1500
  let maxModelTurns = 2

  for (const id of activeProfiles) {
    const spec = INTENT_PROFILES[id]
    if (!spec) continue
    for (const tool of spec.tools) allowSet.add(tool)
    targetFirstTokenMs = Math.max(targetFirstTokenMs, spec.targetFirstTokenMs)
    maxModelTurns = Math.max(maxModelTurns, spec.maxModelTurns)
  }

  const allowedTools = allNames.length > 0
    ? allNames.filter(name => allowSet.has(name))
    : [...allowSet]

  return {
    profile: activeProfiles.length === 1 ? activeProfiles[0] : 'composite',
    matchedProfiles: activeProfiles,
    allowedTools,
    prunedCount: Math.max(0, allNames.length - allowedTools.length),
    targetFirstTokenMs,
    maxModelTurns,
  }
}

/**
 * 完整的 Personal Super Agent 单轮执行规划：
 * 结合最小工具集裁剪、用户追问抢占车道、后台 Worker 分流与主动承接提示。
 */
export function planSuperagentTurn(inputText, availableTools = [], state = {}) {
  const text = String(inputText ?? '').trim()
  const routed = routeTurnTools(text, availableTools, state)
  const activeWorkers = Math.max(0, Number(state.activeWorkerCount ?? 0))
  const pendingReceipts = Math.max(0, Number(state.pendingReceiptCount ?? 0))

  let executionLane = 'foreground_direct'
  let recommendedWorkerMode = null

  if (activeWorkers > 0 && PREEMPT_PATTERN.test(text)) {
    executionLane = 'user_preempt'
  } else if (routed.matchedProfiles.includes('workshop_create')) {
    executionLane = 'background_worker'
    recommendedWorkerMode = 'create'
  } else if (routed.matchedProfiles.includes('device_screen')) {
    executionLane = 'background_worker'
    recommendedWorkerMode = 'device'
  } else if (HEAVY_WORK_PATTERN.test(text) || routed.profile === 'full' || routed.matchedProfiles.length >= 2) {
    executionLane = 'background_worker'
    recommendedWorkerMode = routed.matchedProfiles.includes('workspace_file') ? 'device' : 'read'
  }

  const proactiveHints = []
  if (pendingReceipts > 0) {
    proactiveHints.push(`有 ${pendingReceipts} 条后台任务新进展待向用户简明汇报`)
  }
  if (executionLane === 'user_preempt') {
    proactiveHints.push('用户发出干预/暂停指令：优先响应前台诉求并同步暂停或调整在途 Worker')
  } else if (executionLane === 'background_worker') {
    proactiveHints.push(`建议派发 ${recommendedWorkerMode} 模式后台 Worker 执行，前台立即确认接单不阻塞对话`)
  }

  return {
    ...routed,
    executionLane,
    recommendedWorkerMode,
    userPriorityPreempt: executionLane === 'user_preempt',
    proactiveHints,
  }
}
