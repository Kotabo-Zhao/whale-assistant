import { lazy, Suspense, useState } from 'react'
import {
  ArrowLeft,
  ExternalLink,
  HardDrive,
  KeyRound,
  Loader2,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  SquareTerminal,
} from 'lucide-react'
import { t } from '../i18n'
import type { RuntimeBridge, RuntimeState, ShizukuState, TerminalKind } from '../platform/types'

const TerminalPanel = lazy(() => import('../components/TerminalPanel').then(m => ({ default: m.TerminalPanel })))

export interface TerminalScreenProps {
  bridge: RuntimeBridge
  fontSize: number
  onAuthorize: () => void
  onBack: () => void
  onConnect: () => void
  onError: (message: string) => void
  onOpenEnvironment: () => void
  onOpenShizuku: () => void
  runtime: RuntimeState
  shizuku: ShizukuState
}

function runtimeInstalled(runtime: RuntimeState): boolean {
  return runtime.installedVersion !== undefined || ['ready', 'running', 'stopping'].includes(runtime.phase)
}

function runtimeTransitioning(runtime: RuntimeState): boolean {
  return ['preparing', 'downloading', 'verifying', 'extracting'].includes(runtime.phase)
}

export function TerminalScreen({
  bridge,
  fontSize,
  onAuthorize,
  onBack,
  onConnect,
  onError,
  onOpenEnvironment,
  onOpenShizuku,
  runtime,
  shizuku,
}: TerminalScreenProps) {
  const [kind, setKind] = useState<TerminalKind>('ubuntu')
  const [epoch, setEpoch] = useState(0)
  const ubuntuReady = runtimeInstalled(runtime) && !runtimeTransitioning(runtime)
  const deviceReady = shizuku.installed && shizuku.running && shizuku.permission === 'granted' && shizuku.connected
  const ready = kind === 'ubuntu' ? ubuntuReady : deviceReady

  return (
    <div className="screen terminal-screen">
      <div className="screen-heading terminal-heading">
        <div>
          <h1>{t('终端')}</h1>
        </div>
        <div className="heading-actions">
          <button className="icon-button" type="button" title={t('重新连接')} aria-label={t('重新连接终端')} onClick={() => setEpoch(value => value + 1)} disabled={!ready}>
            <RefreshCw size={19} />
          </button>
          <button className="icon-button" type="button" title={t('返回设置')} aria-label={t('返回设置')} onClick={onBack}>
            <ArrowLeft size={19} />
          </button>
        </div>
      </div>

      <div className="segmented" role="tablist" aria-label={t('终端类型')}>
        <button type="button" role="tab" aria-selected={kind === 'ubuntu'} className={kind === 'ubuntu' ? 'active' : ''} onClick={() => setKind('ubuntu')}>
          <SquareTerminal size={17} />Ubuntu
        </button>
        <button type="button" role="tab" aria-selected={kind === 'device'} className={kind === 'device' ? 'active' : ''} onClick={() => setKind('device')}>
          <Smartphone size={17} />{t('设备 Shell')}
        </button>
      </div>

      {ready ? (
        <Suspense fallback={<div className="terminal-loading"><Loader2 className="spin" size={22} /></div>}>
          <TerminalPanel key={`${kind}-${epoch}`} bridge={bridge} fontSize={fontSize} kind={kind} onError={onError} />
        </Suspense>
      ) : kind === 'ubuntu' ? (
        <div className="empty-terminal">
          <span><HardDrive size={27} /></span>
          <h2>{t('Ubuntu 尚未就绪')}</h2>
          <button className="button button-primary" type="button" onClick={onOpenEnvironment}>
            {t('前往运行环境')}
          </button>
        </div>
      ) : (
        <div className="empty-terminal">
          <span><KeyRound size={27} /></span>
          <h2>
            {!shizuku.installed
              ? t('未安装 Shizuku')
              : !shizuku.running
                ? t('Shizuku 未运行{0}', shizuku.appVersion ? '（' + shizuku.appVersion + '）' : '')
                : shizuku.permission !== 'granted'
                  ? t('需要 Shizuku 授权')
                  : t('Shizuku 连接未就绪')}
          </h2>
          {shizuku.installed && !shizuku.running && (
            <p className="shizuku-hint">{t('Shizuku 服务不会自动启动：请在 Shizuku App 内通过无线调试或 adb 启动服务（设备重启后需重新启动）。')}</p>
          )}
          {shizuku.installed ? (
            <button className="button button-primary" type="button" onClick={!shizuku.running ? onOpenShizuku : shizuku.permission === 'granted' ? onConnect : onAuthorize}>
              {shizuku.permission === 'granted' && shizuku.running ? <RefreshCw size={18} /> : <ShieldCheck size={18} />}
              {!shizuku.running ? t('打开 Shizuku') : shizuku.permission === 'granted' ? t('连接 Shizuku') : t('请求授权')}
            </button>
          ) : (
            <button className="button button-secondary" type="button" onClick={onOpenShizuku}>
              <ExternalLink size={18} />{t('打开 Shizuku')}
            </button>
          )}
        </div>
      )}
    </div>
  )
}
