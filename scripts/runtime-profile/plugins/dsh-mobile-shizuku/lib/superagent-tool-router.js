/**
 * Whale Super Agent 意图路由与最小工具集裁剪器。
 *
 * 解决主会话首轮携带全量工具定义导致约 3 万 token 膨胀、响应慢与功耗高的问题：
 * 按用户输入与当前上下文意图，为 Router 选择最小必需工具集与交互时延预算；
 * 遇到多域复合任务或显式兜底时自动升级为 full，避免误裁导致任务卡住。
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
})

const SIGNALS = [
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

/**
 * 根据用户输入与选项，决定 Router 本轮应加载的意图档位与最小工具集。
 * 若命中 >= 2 个不同工具域，自动合并所需工具集；若显式要求全量或包含脚本/代码执行需求，则回落 full。
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
