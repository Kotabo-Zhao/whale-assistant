/**
 * Whale Assistant 极简设置分组与三档自治策略模型。
 *
 * 将原本分散在 20+ 细粒度开关中的权限与配置收敛为直观的 3 档自治模式与 4 组设置结构，
 * 供前端设置页与策略桥接层共享。
 */

export type AutonomyPresetId = 'fluent' | 'balanced' | 'strict'

export interface AutonomyPreset {
  id: AutonomyPresetId
  label: string
  description: string
  autoAllowReadOnly: boolean
  autoAllowWorkspaceWrite: boolean
  taskScopedApproval: boolean
  /** 系统级底线：删除外部文件、支付、密码/验证码与危险系统命令在任何档位都必须人工确认或拒绝。 */
  preserveSystemSafetyGuards: true
}

export const AUTONOMY_PRESETS: readonly AutonomyPreset[] = [
  {
    id: 'fluent',
    label: '流畅模式（推荐）',
    description: '只读查询、记忆与资料读写、工作区文件操作自动放行；高影响外部操作与系统敏感界面仍严格拦截确认。',
    autoAllowReadOnly: true,
    autoAllowWorkspaceWrite: true,
    taskScopedApproval: true,
    preserveSystemSafetyGuards: true,
  },
  {
    id: 'balanced',
    label: '平衡模式',
    description: '只读查询自动放行；写入本地资料或工作区文件时按任务确认一次。',
    autoAllowReadOnly: true,
    autoAllowWorkspaceWrite: false,
    taskScopedApproval: true,
    preserveSystemSafetyGuards: true,
  },
  {
    id: 'strict',
    label: '严格模式',
    description: '除基础对话外，涉及文件写入或设备操作的工具调用均需逐项确认。',
    autoAllowReadOnly: false,
    autoAllowWorkspaceWrite: false,
    taskScopedApproval: false,
    preserveSystemSafetyGuards: true,
  },
] as const

export const DEFAULT_AUTONOMY_PRESET: AutonomyPresetId = 'fluent'

export function resolveAutonomyPreset(id: string | null | undefined): AutonomyPreset {
  return AUTONOMY_PRESETS.find(item => item.id === id) ?? AUTONOMY_PRESETS[0]
}

export interface CompactSettingsGroup {
  id: 'model_appearance' | 'permissions_autonomy' | 'device_capabilities' | 'runtime_advanced'
  label: string
  summary: string
  collapsedByDefault: boolean
}

export const COMPACT_SETTINGS_GROUPS: readonly CompactSettingsGroup[] = [
  {
    id: 'model_appearance',
    label: '模型与外观',
    summary: '服务商密钥、默认模型、浅/深主题与语言',
    collapsedByDefault: false,
  },
  {
    id: 'permissions_autonomy',
    label: '权限与自治',
    summary: '流畅/平衡/严格模式、记忆与资料读写授权',
    collapsedByDefault: false,
  },
  {
    id: 'device_capabilities',
    label: '手机能力',
    summary: '悬浮桌宠、无障碍、Shizuku 与目标应用副屏',
    collapsedByDefault: true,
  },
  {
    id: 'runtime_advanced',
    label: '环境与开发者选项',
    summary: '运行环境休眠、存储清理、终端与诊断日志',
    collapsedByDefault: true,
  },
] as const
