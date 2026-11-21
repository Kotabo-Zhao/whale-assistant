import { useEffect, useState } from 'react'
import { Bell, Accessibility, Sparkles, Volume2, Mic } from 'lucide-react'
import { t } from '../i18n'
import { WhalePowerHub } from './WhalePowerHub'
import { runtimeBridge } from '../platform/native'
import type { CompanionQuickSettings, WhaleVoiceConfig } from '../platform/types'
import {
  AUTONOMY_PRESETS,
  DEFAULT_AUTONOMY_PRESET,
  resolveAutonomyPreset,
  type AutonomyPresetId,
} from '../ui/whaleUiPreferences'

const STORAGE_KEY = 'whale.autonomyPreset.v1'

type MasterSwitchKey = 'masterPetFullAccess' | 'masterMemoryAndRag' | 'masterProactiveAndNotify'

const MASTER_SWITCHES: Array<{ key: MasterSwitchKey; title: string; hint: string }> = [
  {
    key: 'masterPetFullAccess',
    title: '桌宠与后台 Worker 完全权限',
    hint: '赋予桌宠与 App 主界面同等完全权限，可直接读写记忆资料、调用工具并派发后台 Worker。',
  },
  {
    key: 'masterMemoryAndRag',
    title: '记忆、资料库与后台理解全自动',
    hint: '合并长期记忆、资料库读写、自动提取偏好与后台语义理解，一键全开。',
  },
  {
    key: 'masterProactiveAndNotify',
    title: '主动陪伴、通知与日程简报',
    hint: '合并通知摘要、桌面气泡、日历读取、休息提醒与每日简报。',
  },
]

const VOICE_OPTIONS: Array<{ id: string; label: string }> = [
  { id: 'diana', label: 'Diana · 欢快女声（推荐）' },
  { id: 'claire', label: 'Claire · 温柔女声' },
  { id: 'bella', label: 'Bella · 活力女声' },
  { id: 'anna', label: 'Anna · 沉稳女声' },
  { id: 'alex', label: 'Alex · 沉稳男声' },
  { id: 'benjamin', label: 'Benjamin · 低沉男声' },
]

const PRESET_MASTER_FLAGS: Record<AutonomyPresetId, Record<MasterSwitchKey, boolean>> = {
  fluent: {
    masterPetFullAccess: true,
    masterMemoryAndRag: true,
    masterProactiveAndNotify: true,
  },
  balanced: {
    masterPetFullAccess: true,
    masterMemoryAndRag: true,
    masterProactiveAndNotify: false,
  },
  strict: {
    masterPetFullAccess: false,
    masterMemoryAndRag: false,
    masterProactiveAndNotify: false,
  },
}

function readMasterState(snapshot: CompanionQuickSettings | null): Record<MasterSwitchKey, boolean> {
  if (!snapshot) {
    return {
      masterPetFullAccess: true,
      masterMemoryAndRag: true,
      masterProactiveAndNotify: false,
    }
  }
  return {
    masterPetFullAccess:
      snapshot.masterPetFullAccess ?? (snapshot.bubbleMemoryAccess && snapshot.knowledgeModelAccess),
    masterMemoryAndRag:
      snapshot.masterMemoryAndRag ??
      (snapshot.memoryEnabled && snapshot.knowledgeModelAccess && snapshot.knowledgeModelWrite),
    masterProactiveAndNotify:
      snapshot.masterProactiveAndNotify ??
      (snapshot.notificationCapture && snapshot.notificationBubbles && snapshot.reminders),
  }
}

export interface WhaleAutonomyCardProps {
  onOpenOverlay?: () => void
  onOpenAccessibility?: () => void
  onOpenNotifications?: () => void
}

export function WhaleAutonomyCard({
  onOpenOverlay,
  onOpenAccessibility,
  onOpenNotifications,
}: WhaleAutonomyCardProps) {
  const [autonomyId, setAutonomyId] = useState<AutonomyPresetId>(() => {
    try {
      return resolveAutonomyPreset(window.localStorage.getItem(STORAGE_KEY)).id
    } catch {
      return DEFAULT_AUTONOMY_PRESET
    }
  })
  const [masters, setMasters] = useState<Record<MasterSwitchKey, boolean>>(() =>
    PRESET_MASTER_FLAGS[resolveAutonomyPreset(autonomyId).id],
  )
  const [syncState, setSyncState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const [voiceCfg, setVoiceCfg] = useState<WhaleVoiceConfig>({
    configured: false,
    baseUrl: 'https://api.siliconflow.cn/v1',
    voice: 'diana',
    speed: 1.05,
    autoSpeakPet: true,
    hasRecordPermission: false,
  })
  const [voiceKeyInput, setVoiceKeyInput] = useState('')
  const [voiceStatus, setVoiceStatus] = useState('')
  const [petSizeDp, setPetSizeDp] = useState(88)

  useEffect(() => {
    let active = true
    void Promise.resolve()
      .then(() => runtimeBridge.getCompanionQuickSettings())
      .then(snapshot => {
        if (active && snapshot) setMasters(readMasterState(snapshot))
      })
      .catch(() => {
        // Keep local preset defaults on web preview.
      })
    if (runtimeBridge.getVoiceConfig) {
      void runtimeBridge.getVoiceConfig()
        .then(cfg => { if (active && cfg) setVoiceCfg(cfg) })
        .catch(() => undefined)
    }
    if (runtimeBridge.getPetSize) {
      void runtimeBridge.getPetSize()
        .then(res => { if (active && res && typeof res.sizeDp === 'number') setPetSizeDp(res.sizeDp) })
        .catch(() => undefined)
    }
    return () => {
      active = false
    }
  }, [])

  const activeAutonomy = resolveAutonomyPreset(autonomyId)

  const selectAutonomy = async (id: AutonomyPresetId): Promise<void> => {
    setAutonomyId(id)
    const nextMasters = PRESET_MASTER_FLAGS[id]
    setMasters(nextMasters)
    setSyncState('saving')
    try {
      window.localStorage.setItem(STORAGE_KEY, id)
    } catch {
      // Ignore private-mode storage errors.
    }
    try {
      let latest: CompanionQuickSettings | null = null
      for (const key of Object.keys(nextMasters) as MasterSwitchKey[]) {
        latest = await runtimeBridge.setCompanionQuickSetting(key, nextMasters[key])
      }
      if (latest) setMasters(readMasterState(latest))
      setSyncState('saved')
    } catch {
      setSyncState('error')
    }
  }

  const toggleMaster = async (key: MasterSwitchKey, value: boolean): Promise<void> => {
    setMasters(prev => ({ ...prev, [key]: value }))
    setSyncState('saving')
    try {
      const latest = await runtimeBridge.setCompanionQuickSetting(key, value)
      if (latest) setMasters(readMasterState(latest))
      setSyncState('saved')
    } catch {
      setSyncState('error')
    }
  }

  const saveVoice = async (patch: { apiKey?: string; voice?: string; autoSpeakPet?: boolean }): Promise<void> => {
    if (!runtimeBridge.saveVoiceConfig) return
    try {
      const next = await runtimeBridge.saveVoiceConfig(patch)
      setVoiceCfg(next)
      if (patch.apiKey !== undefined) setVoiceKeyInput('')
      setVoiceStatus(t('语音配置已保存'))
    } catch {
      setVoiceStatus('')
    }
  }

  return (
    <section className="whale-autonomy-card" aria-label={t('执行与权限模式')}>
      <div className="whale-autonomy-head">
        <strong>{t('一站式权限与自治')}</strong>
        <span>
          {syncState === 'saving'
            ? t('同步中…')
            : syncState === 'saved'
              ? `${t(activeAutonomy.label)} · ${t('已生效')}`
              : t(activeAutonomy.label)}
        </span>
      </div>
      <div className="whale-autonomy-pills" role="radiogroup" aria-label={t('自治模式')}>
        {AUTONOMY_PRESETS.map(preset => (
          <button
            key={preset.id}
            type="button"
            role="radio"
            aria-checked={autonomyId === preset.id}
            disabled={syncState === 'saving'}
            className={'whale-autonomy-pill' + (autonomyId === preset.id ? ' is-active' : '')}
            onClick={() => { void selectAutonomy(preset.id) }}
          >
            {t(preset.label)}
          </button>
        ))}
      </div>
      <p className="whale-autonomy-desc">{t(activeAutonomy.description)}</p>
      <div className="whale-master-switches" aria-label={t('核心权限总开关')}>
        {MASTER_SWITCHES.map(item => (
          <label key={item.key} className="whale-master-row">
            <span className="whale-master-copy">
              <strong>{t(item.title)}</strong>
              <small>{t(item.hint)}</small>
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={masters[item.key]}
              disabled={syncState === 'saving'}
              onChange={event => { void toggleMaster(item.key, event.target.checked) }}
            />
          </label>
        ))}
      </div>

      <details className="whale-voice-box whale-collapsible">
        <summary className="whale-voice-head" style={{ cursor: 'pointer', listStyle: 'none' }}>
          <strong>{t('桌宠尺寸与拟人语音')}</strong>
          <span>{petSizeDp}dp · {voiceStatus || (voiceCfg.configured ? t('语音已配') : t('语音未配'))}</span>
        </summary>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', marginTop: '10px' }}>
          <div className="whale-size-slider-row">
            <small>44</small>
            <input
              type="range"
              min={44}
              max={160}
              step={1}
              value={petSizeDp}
              aria-label={t('桌宠大小无级调节')}
              onChange={e => {
                const next = Number(e.target.value)
                setPetSizeDp(next)
                void runtimeBridge.setPetSize?.(next).catch(() => undefined)
              }}
            />
            <small>160</small>
          </div>
          <div className="whale-voice-row">
            <input
              type="password"
              value={voiceKeyInput}
              placeholder={t('硅基流动 API Key（免费听 + 拟人说）')}
              onChange={e => setVoiceKeyInput(e.target.value)}
            />
            <button
              type="button"
              className="whale-perm-chip"
              disabled={!voiceKeyInput.trim()}
              onClick={() => { void saveVoice({ apiKey: voiceKeyInput.trim() }) }}
            >
              {t('保存语音 Key')}
            </button>
          </div>
          <div className="whale-voice-row">
            <select
              aria-label={t('选择桌宠音色')}
              value={voiceCfg.voice}
              onChange={e => { void saveVoice({ voice: e.target.value }) }}
            >
              {VOICE_OPTIONS.map(opt => (
                <option key={opt.id} value={opt.id}>{opt.label}</option>
              ))}
            </select>
            <button
              type="button"
              className="whale-perm-chip"
              disabled={!voiceCfg.configured}
              onClick={() => { void runtimeBridge.testVoiceSpeak?.() }}
            >
              <Volume2 size={13} aria-hidden="true" />
              {t('试听一声')}
            </button>
            <button
              type="button"
              className="whale-perm-chip"
              onClick={() => {
                void runtimeBridge.requestRecordAudioPermission?.().then(res => {
                  if (res?.granted) setVoiceCfg(prev => ({ ...prev, hasRecordPermission: true }))
                })
              }}
            >
              <Mic size={13} aria-hidden="true" />
              {voiceCfg.hasRecordPermission ? t('麦克风已授权') : t('授予麦克风权限')}
            </button>
          </div>
        </div>
      </details>

      <WhalePowerHub />

      {(onOpenOverlay || onOpenAccessibility || onOpenNotifications) && (
        <div className="whale-perm-shortcuts" aria-label={t('系统权限快捷授权')}>
          {onOpenOverlay && (
            <button type="button" className="whale-perm-chip" onClick={onOpenOverlay}>
              <Sparkles size={13} aria-hidden="true" />
              {t('悬浮桌宠权限')}
            </button>
          )}
          {onOpenAccessibility && (
            <button type="button" className="whale-perm-chip" onClick={onOpenAccessibility}>
              <Accessibility size={13} aria-hidden="true" />
              {t('无障碍屏控')}
            </button>
          )}
          {onOpenNotifications && (
            <button type="button" className="whale-perm-chip" onClick={onOpenNotifications}>
              <Bell size={13} aria-hidden="true" />
              {t('通知与提醒')}
            </button>
          )}
        </div>
      )}
    </section>
  )
}
