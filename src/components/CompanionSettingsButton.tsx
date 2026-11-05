import { useEffect, useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings } from '../platform/types'
import './CompanionSettingsButton.css'

const options: { key: keyof CompanionQuickSettings; title: string; hint: string }[] = [
  { key: 'memoryEnabled', title: '允许使用长期记忆', hint: '已确认的记忆可供助手检索，相关内容可能发送给当前模型服务。' },
  { key: 'bubbleMemoryAccess', title: '允许桌宠访问长期记忆', hint: '需要同时开启长期记忆。允许桌宠按需检索、保存和按你的要求修改记忆；相关内容可能发送给当前模型服务。' },
  { key: 'contextFollowup', title: '结合最近对话和资料主动跟进', hint: '新对话结束或资料更新后，桌宠会匹配近 7 天的对话，将最多 4 条对话摘录、1 段相关资料及已提醒的相关事项名交给后台模型判断。只在空闲时提醒，同一件事会去重。需开启资料检索。' },
  { key: 'knowledgeModelWrite', title: '允许对话写入资料库', hint: '主对话和桌宠可按你的要求保存文字。修改已有笔记还需开启资料检索。' },
  { key: 'knowledgeModelAccess', title: '允许对话检索本地资料', hint: '开启后检索到的资料可能发送给当前模型服务。' },
  { key: 'automaticAssistantMemory', title: '自动记住对话中的偏好', hint: '开启后助手提出的长期事实会直接保存，无需逐条确认；可能记错，可随时查看、修改或删除。' },
  { key: 'automaticUsageMemory', title: '自动整理应用使用习惯', hint: '需要系统使用情况访问权限和悬浮助手运行；本机汇总近 7 天使用时长，不读取应用内部内容。检索时可能发送给模型。' },
  { key: 'automaticNotificationMemory', title: '自动记住其他应用通知', hint: '需要开启通知摘要及系统通知读取权限。符合条件的新通知会自动作为未经核实的记忆保存在本机；检索时可能发送给模型。' },
  { key: 'notificationMemorySuggestions', title: '从通知中寻找记忆候选', hint: '仅在本机生成候选，需要核对后才能保存。' },
  { key: 'notificationCapture', title: '收集通知摘要', hint: '保存在本机；还需要授予系统通知读取权限，敏感信息过滤并不完整。' },
  { key: 'notificationBubbles', title: '通知桌面气泡', hint: '通知到达时在悬浮助手显示气泡，需要悬浮窗权限。' },
  { key: 'candidateNotifications', title: '重要事项通知', hint: '识别可能的待办并发送系统通知，识别结果可能有误。' },
  { key: 'reminders', title: '休息提醒', hint: '悬浮助手运行且手机解锁时提醒休息。' },
  { key: 'quietHoursEnabled', title: '启用免打扰', hint: '免打扰时段内暂停气泡和休息提示；时间可在更多设置中调整。' },
]

export function CompanionSettingsButton({ disabled, openMemory }: { disabled: boolean; openMemory?: () => void }) {
  const [settings, setSettings] = useState<CompanionQuickSettings | null>(null)
  const [working, setWorking] = useState(false)
  const [message, setMessage] = useState('')
  useEffect(() => {
    let live = true
    void Promise.resolve().then(() => runtimeBridge.getCompanionQuickSettings())
      .then(value => { if (live) setSettings(value) })
      .catch(() => { if (live) setMessage('无法读取助手设置，请在安卓应用中重试') })
    return () => { live = false }
  }, [])
  const update = async (field: keyof CompanionQuickSettings, value: boolean) => {
    setWorking(true); setMessage('')
    try { setSettings(await runtimeBridge.setCompanionQuickSetting(field, value)) }
    catch { setMessage('保存助手设置失败，请重试') }
    finally { setWorking(false) }
  }
  return <section className="companion-settings" aria-label={t('助手偏好')}>
    {['记忆与资料', '通知与提醒'].map((group, index) => <details key={group} className="companion-settings-group"><summary><strong>{t(group)}</strong><small>{t(index === 0 ? '管理助手能记住和使用的信息' : '选择哪些提醒可以出现')}</small></summary>
    {options.filter(option => ['memoryEnabled', 'bubbleMemoryAccess', 'knowledgeModelAccess', 'knowledgeModelWrite', 'contextFollowup', 'automaticAssistantMemory', 'automaticUsageMemory', 'automaticNotificationMemory'].includes(option.key) === (index === 0)).map(option => <label className="toggle-row" key={option.key}>
      <span><strong>{t(option.title)}</strong><small>{t(option.hint)}</small></span>
      <input type="checkbox" role="switch" checked={settings?.[option.key] ?? false} disabled={disabled || working || settings === null}
        onChange={event => { const value = event.target.checked; if (value && option.key === 'knowledgeModelAccess' && !window.confirm('允许助手检索资料？相关内容可能发送给当前模型服务。')) return; if (value && option.key === 'bubbleMemoryAccess' && !window.confirm('允许桌宠访问长期记忆？相关内容可能发送给当前模型服务。')) return; void update(option.key, value) }} />
    </label>)}
    </details>)}
    {settings?.contextFollowup && !settings.knowledgeModelAccess && <p className="workspace-hint" role="status">{t('开启「允许对话检索本地资料」后，桌宠才能根据资料跟进。')}</p>}
    {settings?.memoryEnabled && !settings.bubbleMemoryAccess && <p className="workspace-hint" role="status">长期记忆已开启，但桌宠尚未获准访问。请在“记忆与资料”中开启“允许桌宠访问长期记忆”。</p>}
    {openMemory && <button className="button button-secondary" type="button" onClick={openMemory}>{t('打开记忆')}</button>}
    <button className="button button-secondary" type="button" disabled={disabled || working} onClick={() => {
      setMessage(''); void runtimeBridge.openCompanionSettings().catch(() => setMessage('无法打开助手设置，请在安卓应用中重试'))
    }}>{t('全部助手偏好')}</button>
    {message && <p className="status-text-error" role="alert">{t(message)}</p>}
  </section>
}
