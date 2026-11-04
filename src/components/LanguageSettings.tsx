import { runtimeBridge } from '../platform/native'
import { useState } from 'react'
import { type Language, saveLanguage, t, useLanguage } from '../i18n'

export function LanguageSettings() {
  const language = useLanguage()
  const [failed, setFailed] = useState(false)

  const [saving, setSaving] = useState(false)
  const change = async (value: Language) => {
    setSaving(true)
    setFailed(false)
    try {
      await runtimeBridge.setAppLanguage(value)
      setFailed(!saveLanguage(value))
    } catch {
      setFailed(true)
    } finally {
      setSaving(false)
    }
  }

  return <div className="language-control account-language-control">
    <label htmlFor="app-language">{t('语言')}</label>
    <select disabled={saving} id="app-language" value={language} onChange={event => {
      const value = event.target.value
      if (value === 'zh-CN' || value === 'en') void change(value)
    }}>
      <option value="zh-CN" lang="zh-CN">简体中文</option><option value="en" lang="en">English</option>
    </select>
    {failed && <p role="alert">无法保存语言，请重试。 / Unable to save language.</p>}
  </div>
}
