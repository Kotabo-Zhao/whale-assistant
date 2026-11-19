import { History, MessageSquare, Settings2 } from 'lucide-react'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanBadge from '../assets/whale-chan-badge.png'
import { t, useLanguage } from '../i18n'
import { useInterfaceStyle } from '../theme'
import { primaryDestination, type AppView } from '../ui/appNavigation'

interface AppSidebarProps {
  activeView: AppView
  onNavigate: (view: AppView) => void
}

/** 主路径只留对话和设置。资料、任务、工具从对话快捷入口和「我的 → 日常」进入。 */
const destinations = [
  { view: 'whale-chat', label: '对话', Icon: MessageSquare },
  { view: 'settings', label: '我的', Icon: Settings2 },
] as const

function DestinationButtons({ activeView, onNavigate, mobile }: AppSidebarProps & { mobile?: boolean }) {
  const parent = primaryDestination(activeView)
  return <>{destinations.map(({ view, label, Icon }) => {
    const active = parent === view || (view === 'whale-chat' && (parent === 'conversation' || parent === 'harness'))
    return <button key={view} type="button" className={[(mobile ? 'bottom-navigation-item' : 'sidebar-item'), active ? 'is-active' : ''].join(' ')}
      aria-label={t(label)} aria-current={active ? 'page' : undefined} title={t(label)} onClick={() => onNavigate(view)}>
      <Icon size={20} aria-hidden="true" /><span>{t(label)}</span></button>
  })}</>
}

export function AppSidebar({ activeView, onNavigate }: AppSidebarProps) {
  useLanguage()
  const style = useInterfaceStyle()
  const brandMark = style === 'simple' ? grokMark : whaleChanBadge
  return <aside className="app-sidebar" aria-label={t('应用导航')}>
    <button className="sidebar-mark" type="button" aria-label={t('鲸鱼助手')} title={t('鲸鱼助手')} onClick={() => onNavigate('whale-chat')}>
      <img className="app-mark" src={brandMark} alt="" width={28} height={28}/><span>{t('鲸鱼助手')}</span></button>
    <nav className="sidebar-nav"><DestinationButtons activeView={activeView} onNavigate={onNavigate}/></nav>
    <div className="sidebar-bottom"><button className={'sidebar-item' + (activeView === 'sessions' ? ' is-active' : '')}
      aria-label={t('会话管理')} title={t('会话管理')} type="button" onClick={() => onNavigate('sessions')}>
      <History size={18}/><span>{t('会话')}</span></button></div>
  </aside>
}
export function BottomNavigation(props: AppSidebarProps) {
  useLanguage()
  return <nav className="bottom-navigation" aria-label={t('底部导航')}><DestinationButtons {...props} mobile /></nav>
}
