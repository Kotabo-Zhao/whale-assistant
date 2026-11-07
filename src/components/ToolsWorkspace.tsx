import { ChevronRight, Blocks, Hammer } from 'lucide-react'
import { AuthoredTools } from './AuthoredTools'
import { runtimeBridge } from '../platform/native'
import { t } from '../i18n'

export function ToolsWorkspace({ plugins }: { plugins: () => void }) {
  return <section className="screen tools-workspace">
    <header className="desk-heading page-heading"><div><h1>{t('工具')}</h1><p>{t('打开已启用的工具，或从功能工坊开始创作。')}</p></div><Hammer size={24} aria-hidden="true" /></header>
    <AuthoredTools bridge={runtimeBridge} create />
    <button className="tools-plugin-link" type="button" onClick={plugins}>
      <Blocks size={22} aria-hidden="true" /><span><strong>{t('插件管理')}</strong><small>{t('安装、配置与维护 Harness 插件')}</small></span><ChevronRight size={18} />
    </button>
  </section>
}
