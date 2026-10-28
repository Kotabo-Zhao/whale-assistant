import { useState } from 'react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'

export function CompanionSettingsButton({ disabled }: { disabled: boolean }) {
  const [failed, setFailed] = useState(false)
  return <div>
    <button className="button button-secondary" type="button" disabled={disabled} onClick={() => {
      setFailed(false)
      void runtimeBridge.openCompanionSettings().catch(() => setFailed(true))
    }}>{t('鲸鱼娘助手设置')}</button>
    {failed && <p role="alert">{t('无法打开助手设置，请在安卓应用中重试')}</p>}
  </div>
}
