import { useEffect, useState } from 'react'
import { Mic, Volume2, SlidersHorizontal } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings } from '../platform/types'
import './CompanionSettingsButton.css'

const options: { key: keyof CompanionQuickSettings; title: string; hint: string }[] = [
  { key: 'memoryEnabled', title: '允许使用长期记忆', hint: '已确认的记忆可供助手检索，相关内容可能发送给当前模型服务。' },
  { key: 'notificationCapture', title: '收集通知摘要', hint: '保存在本机；还需要授予系统通知读取权限，敏感信息过滤并不完整。' },
  { key: 'notificationBubbles', title: '通知桌面气泡', hint: '通知到达时在悬浮助手显示气泡，需要悬浮窗权限。' },
  { key: 'candidateNotifications', title: '重要事项通知', hint: '识别可能的待办并发送系统通知，识别结果可能有误。' },
  { key: 'reminders', title: '休息提醒', hint: '悬浮助手运行且手机解锁时提醒休息。' },
  { key: 'quietHoursEnabled', title: '启用免打扰', hint: '免打扰时段内暂停气泡和休息提示；时间可在更多设置中调整。' },
]

export function CompanionSettingsButton({ disabled }: { disabled: boolean }) {
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
  const tool = async (action: 'voice' | 'speak') => {
    setWorking(true); setMessage('')
    try { await runtimeBridge.runHarnessAssistantTool(action) }
    catch { setMessage('请先打开对话标签并等待页面就绪，再使用语音功能') }
    finally { setWorking(false) }
  }
  return <section className="companion-settings" aria-labelledby="companion-settings-title">
    <div className="companion-settings-intro"><span className="companion-settings-icon"><SlidersHorizontal size={20} /></span>
      <div><h3 id="companion-settings-title">{t('鲸鱼娘与语音')}</h3><p>{t('桌面提醒、长期记忆与对话语音，集中在这里管理。')}</p></div>
    </div>
    <div className="companion-voice-actions">
      <button className="button button-secondary" type="button" disabled={disabled || working} onClick={() => { void tool('voice') }}><Mic size={18} />{t('语音输入')}</button>
      <button className="button button-secondary" type="button" disabled={disabled || working} onClick={() => { void tool('speak') }}><Volume2 size={18} />{t('朗读当前回复')}</button>
    </div>
    <p className="plugin-hint">{t('语音输入使用系统识别服务，核对后填入草稿，不会自动发送；朗读前会先展示内容。')}</p>
    {options.map(option => <label className="toggle-row" key={option.key}>
      <span><strong>{t(option.title)}</strong><small>{t(option.hint)}</small></span>
      <input type="checkbox" role="switch" checked={settings?.[option.key] ?? false} disabled={disabled || working || settings === null}
        onChange={event => { void update(option.key, event.target.checked) }} />
    </label>)}
    <button className="button button-secondary" type="button" disabled={disabled || working} onClick={() => {
      setMessage(''); void runtimeBridge.openCompanionSettings().catch(() => setMessage('无法打开助手设置，请在安卓应用中重试'))
    }}>{t('更多提醒、资料与记忆管理')}</button>
    {message && <p className="status-text-error" role="alert">{t(message)}</p>}
  </section>
}
