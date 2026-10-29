import { useCallback, useEffect, useRef, useState } from 'react'
import { ListChecks } from 'lucide-react'
import { t } from '../i18n'
import { runtimeBridge } from '../platform/native'
import type { AutomationTask, AutomationTaskState, RuntimeBridge } from '../platform/types'

/** 任务状态 → 一句人话。没登记过的状态回原样：两侧不同步时把技术取值露出来比藏起来更容易查。 */
const TASK_STATUS_LABELS: Record<string, string> = {
  planned: '已计划，还没有发出任何一步',
  running: '正在执行',
  done: '已经完成',
  failed: '失败了',
  blocked: '被挡住了：目标变了，要重新读页面再继续',
  cancelled: '已经停止',
}

/**
 * 步骤状态 → 一句人话。
 *
 * `unknown` 是**结论**而不是「还不知道」：动作发出去了但没观察到预期变化、或者超时，
 * 都记 `unknown`，并且永不自动重发——对发送、提交这类动作，重发就是重复下单。
 */
const STEP_STATUS_LABELS: Record<string, string> = {
  pending: '待执行',
  ok: '已完成',
  failed: '失败',
  unknown: '结果不确定，不要重发',
  skipped: '已跳过（任务停止）',
}

/** 动作用界面名称，只为了让这一步在做什么能被看懂。 */
const ACTION_LABELS: Record<string, string> = {
  click: '点击',
  setText: '输入文字',
  scroll: '滚动',
  back: '返回',
  wait: '等待',
}

/** 取消失败的受控码 → 一句人话。认不出的码回落到「停止失败（码）」，不编原因。 */
const CANCEL_FAILURE_NOTES: Record<string, string> = {
  ACCESSIBILITY_SERVICE_DISABLED: '无障碍服务没有连上，现在叫不停它',
  AUTOMATION_TASK_NOT_PLANNED: '现在没有任务可停止',
  AUTOMATION_TASK_CHANGED: '任务已变化，请重新读取后再停止',
  TASK_IDENTITY_UNAVAILABLE: '当前任务缺少实例标识，请重新读取或更新应用',
  STOP_UNCONFIRMED: '未确认任务已停止，请重新读取状态',
  AUTOMATION_TASK_ALREADY_SETTLED: '任务已经结束，请重新读取状态',
}

/** 受控码从错误里取出来；取不到就给 `UNKNOWN`，不假装知道原因。 */
function failureCode(error: unknown): string {
  if (!error || typeof error !== 'object') return 'UNKNOWN'
  const controlled = (value: unknown): value is string => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/u.test(value)
  if ('code' in error && controlled(error.code)) return error.code
  if ('message' in error && controlled(error.message)) return error.message
  return 'UNKNOWN'
}

/**
 * 「自动化任务」卡片：AI 按步骤执行时的当前任务，以及**人随时叫停它的地方**。
 *
 * 四条边界：
 *  - **「读不到」与「没有任务」必须分开说**：`connected` 为假时任务一栏是空的，但那表示
 *    「读不到」，不表示「已经收口」。把前者显示成后者，用户会以为刚才那串动作已经结束了。
 *  - **这里只读 + 一个停止按钮**：卡片不新建任务、不改步骤、不替模型续跑。叫停是人的动作，
 *    模型侧没有这条路（`cancelAutomationTask` 只在原生桥与设置页之间）。
 *  - **停止不撤回已经发出去的那一步**：未开始的步骤才标成跳过，界面上写明白，
 *    免得用户以为按一下就什么都没发生过。
 *  - 卡片不写入任何浏览器存储，也不自动轮询——需要最新状态时点「重新读取」。
 */
export function AutomationTaskCard({ bridge = runtimeBridge }: { bridge?: RuntimeBridge }) {
  const [state, setState] = useState<AutomationTaskState | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [stopFailed, setStopFailed] = useState<string | null>(null)
  const [stopped, setStopped] = useState(false)
  const [ackFailed, setAckFailed] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const revision = useRef(0)
  const refresh = useCallback(async () => {
    const current = ++revision.current
    setRefreshing(true); setFailed(false); setStopped(false); setStopFailed(null); setAckFailed(false)
    try {
      const result = await bridge.getAutomationTaskState()
      if (revision.current === current) { setState(result); setLoaded(true) }
    } catch { if (revision.current === current) setFailed(true) }
    finally { if (revision.current === current) setRefreshing(false) }
  }, [bridge])
  useEffect(() => { const generation = revision; void refresh(); return () => { generation.current++ } }, [refresh])

  const stop = useCallback(async () => {
    const instanceId = state?.task?.instanceId
    if (!instanceId) { setStopFailed('TASK_IDENTITY_UNAVAILABLE'); return }
    const current = ++revision.current
    setRefreshing(false); setBusy(true); setStopFailed(null); setStopped(false)
    try {
      const result = await bridge.cancelAutomationTask(instanceId)
      if (revision.current === current) {
        setState(result); setLoaded(true); setFailed(false)
        if (result.task?.status === 'cancelled') setStopped(true)
        else if (result.task !== null || !result.connected) setStopFailed('STOP_UNCONFIRMED')
      }
    } catch (error) { if (revision.current === current) setStopFailed(failureCode(error)) }
    finally { if (revision.current === current) setBusy(false) }
  }, [bridge, state])

  const acknowledge = useCallback(async () => {
    const current = ++revision.current
    setRefreshing(false); setBusy(true); setAckFailed(false); setStopped(false); setStopFailed(null)
    try {
      const result = await bridge.acknowledgeInterruptedTask()
      if (revision.current === current) { setState(result); setLoaded(true); setFailed(false) }
    } catch { if (revision.current === current) setAckFailed(true) }
    finally { if (revision.current === current) setBusy(false) }
  }, [bridge])

  const ready = loaded && !failed && state !== null
  const task: AutomationTask | null = ready ? state.task : null

  return <div className="settings-subsection" aria-labelledby="automation-task">
    <div className="section-title"><span className="section-icon"><ListChecks size={19} /></span>
      <div><h3 id="automation-task">{t('自动化任务')}</h3>
        <p>{t('AI 按步骤执行时的当前任务；读不到就读不到，这里也是你随时叫停它的地方。')}</p></div>
    </div>
    {refreshing && <p className="settings-note" role="status">{t(loaded ? '正在刷新，下方是上次读取的状态，可能已变化。' : '正在读取当前任务…')}</p>}
    {ready && state.interrupted === true && <div role="alert" className="settings-note">
      <p>{t('之前的任务可能中断，已发出的操作可能生效。请先检查目标应用，确认后才能开始新任务。')}</p>
      <button className="button button-secondary" type="button" disabled={busy} onClick={() => { void acknowledge() }}>{t('已检查现场，清除中断提示')}</button>
      {ackFailed && <p>{t('无法清除中断提示，请先停止任务或稍后重试。')}</p>}
    </div>}
    {ready && !state.connected && <>
      <p className="settings-note">{t('无障碍服务没有连上，读不到任务状态。')}</p>
      <p className="settings-note">{t('这不是「没有任务」：连接恢复后再读一次才知道现在是什么状态。')}</p>
    </>}
    {ready && state.connected && task === null && state.interrupted !== true && <p className="settings-note">{t('现在没有正在执行的任务。')}</p>}
    {task !== null && <>
      <p className="settings-note">
        <strong>{t('目标应用：{0}', task.packageName)}</strong>
        {` · ${t(task.status === 'done' && task.steps.some(step => step.code === 'AUTOMATION_TASK_ACTION_UNVERIFIED') ? '步骤已结束，部分结果未验证' : TASK_STATUS_LABELS[task.status] ?? task.status)}`}
      </p>
      <p className="settings-note">{t('进度：已收口 {0} 步，待执行 {1} 步，共 {2} 步。', String(task.settledSteps), String(task.pendingSteps), String(task.steps.length))}</p>
      {task.lastMessage !== null && <p className="settings-note">
        {t('最近一次：{0}', task.lastMessage)}
        {task.lastCode !== null ? `（${task.lastCode}）` : ''}
      </p>}
      {task.steps.map(step => <p className="settings-note" key={step.id}>
        <strong>{t(ACTION_LABELS[step.action] ?? step.action)}</strong>
        {` · ${t(step.code === 'AUTOMATION_TASK_ACTION_UNVERIFIED' ? '已记录，结果未验证' : STEP_STATUS_LABELS[step.status] ?? step.status)}`}
        {step.attempts > 1 ? t(' · 已试 {0} 次', String(step.attempts)) : ''}
        {step.code !== null ? `（${step.code}）` : ''}
      </p>)}
      {!['done', 'failed', 'cancelled'].includes(task.status) && <>
        <p className="settings-note">{t('停止会跳过未开始的步骤；已发出的操作不会撤回，结果可能需要检查。')}</p>
        <button className="button button-secondary" type="button" onClick={() => { void stop() }} disabled={busy}>{t('停止这个任务')}</button>
      </>}
      {stopped && <p className="settings-note">{t(task.steps.some(step => step.status === 'unknown')
        ? '已经停止，但有步骤结果不确定；请检查目标应用，不要直接重发。'
        : '已经停止，未开始的步骤已跳过。')}</p>}
    </>}
    {stopFailed !== null && <p role="alert">
      {CANCEL_FAILURE_NOTES[stopFailed] !== undefined ? t(CANCEL_FAILURE_NOTES[stopFailed]) : t('停止失败（{0}）', stopFailed)}
      {' '}
      {['AUTOMATION_TASK_CHANGED', 'AUTOMATION_TASK_ALREADY_SETTLED', 'TASK_IDENTITY_UNAVAILABLE', 'STOP_UNCONFIRMED'].includes(stopFailed)
        ? <button className="button button-quiet" type="button" disabled={busy || refreshing} onClick={() => { void refresh() }}>{t('重新读取')}</button>
        : <button className="button button-quiet" type="button" disabled={busy || refreshing} onClick={() => { void stop() }}>{t('重试')}</button>}
    </p>}
    {ready && <button className="button button-secondary" type="button" disabled={busy || refreshing} onClick={() => { void refresh() }}>{t('重新读取')}</button>}
    {failed && <p role="alert">{t('无法读取当前任务')} <button className="button button-quiet" type="button" onClick={() => { void refresh() }}>{t('重试')}</button></p>}
  </div>
}
