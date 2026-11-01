import type { ReactNode } from 'react'
import { Blocks, Brain, ListTodo, MessageSquare, Settings2 } from 'lucide-react'
import appMark from '../assets/app-mark.png'
import { t, useLanguage } from '../i18n'
import { primaryDestination, type AppView } from '../ui/appNavigation'

interface AppSidebarProps {
  activeView: AppView
  onNavigate: (view: AppView) => void
}

/**
 * 外壳导航轨道：窄屏保留图标，宽屏显示文字。
 *
 * 入口只负责切换外壳视图，真正的 Harness 会话仍由运行时页面管理；
 * 因此侧栏不会把会话正文、凭据或终端输出放进 DOM。
 */
export function AppSidebar({ activeView, onNavigate }: AppSidebarProps) {
  useLanguage()
  const settingsActive = primaryDestination(activeView) === 'settings'
  const item = (view: AppView, label: string, icon: ReactNode, active: boolean, className = '') => (
    <button
      className={`sidebar-item ${active ? 'is-active' : ''} ${className}`}
      type="button"
      aria-label={t(label)}
      aria-current={active ? 'page' : undefined}
      title={t(label)}
      onClick={() => onNavigate(view)}
    >
      {icon}<span>{t(label)}</span>
    </button>
  )

  return (
    <aside className="app-sidebar" aria-label={t('应用导航')}>
      <button
        className={`sidebar-mark ${activeView === 'sessions' ? 'is-active' : ''}`}
        type="button"
        aria-label={t('会话管理')}
        aria-current={activeView === 'sessions' ? 'page' : undefined}
        title={t('会话管理')}
        onClick={() => onNavigate('sessions')}
      >
        <img className="app-mark" src={appMark} alt="" width={26} height={26} />
        <span>{t('会话')}</span>
      </button>
      <nav className="sidebar-nav">
        {item('harness', '对话', <MessageSquare size={20} />, activeView === 'harness' || activeView === 'conversation')}
        {item('assistant-memory', '记忆', <Brain size={20} />, activeView === 'assistant-memory')}
        {item('assistant-tasks', '事项', <ListTodo size={20} />, activeView === 'assistant-tasks')}
        {item('settings', '我的', <Settings2 size={20} />, settingsActive)}
      </nav>
      <div className="sidebar-bottom">
        {item('plugins', '插件管理', <Blocks size={20} />, activeView === 'plugins')}
      </div>
    </aside>
  )
}

/** 手机横竖屏使用底部导航；会话管理由页头鲸鱼入口打开。 */
export function BottomNavigation({ activeView, onNavigate }: AppSidebarProps) {
  useLanguage()
  const items: { view: AppView; label: string; icon: ReactNode; active: boolean }[] = [
    { view: 'harness', label: '对话', icon: <MessageSquare size={20} />, active: activeView === 'harness' || activeView === 'conversation' || activeView === 'sessions' },
    { view: 'assistant-memory', label: '记忆', icon: <Brain size={20} />, active: activeView === 'assistant-memory' },
    { view: 'assistant-tasks', label: '事项', icon: <ListTodo size={20} />, active: activeView === 'assistant-tasks' },
    { view: 'plugins', label: '插件', icon: <Blocks size={20} />, active: activeView === 'plugins' },
    { view: 'settings', label: '我的', icon: <Settings2 size={20} />, active: primaryDestination(activeView) === 'settings' },
  ]
  return (
    <nav className="bottom-navigation" aria-label={t('底部导航')}>
      {items.map(item => (
        <button key={item.view} type="button" className={`bottom-navigation-item ${item.active ? 'is-active' : ''}`}
          aria-label={t(item.view === 'plugins' ? '插件管理' : item.label)} title={t(item.view === 'plugins' ? '插件管理' : item.label)} aria-current={item.active ? 'page' : undefined} onClick={() => onNavigate(item.view)}>
          {item.icon}
          <span>{t(item.label)}</span>
        </button>
      ))}
    </nav>
  )
}
