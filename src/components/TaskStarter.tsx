import { useEffect, useRef, useState } from 'react'
import { ArrowLeftRight, FolderOpen, ScanText, Search } from 'lucide-react'
import { t, useLanguage } from '../i18n'
import { runtimeBridge } from '../platform/native'
import './TaskStarter.css'

const tasks = [
  { icon: Search, title: '查找信息', description: '打开应用，查找并整理结果', prompt: '请打开我指定的应用，查找我需要的信息并整理结果。每次操作后重新观察界面；遇到登录、支付或需要确认的步骤时停下并告诉我。' },
  { icon: FolderOpen, title: '整理文件', description: '先列清单，再按要求整理', prompt: '请先列出我指定位置的文件并提出整理方案，不要直接删除或覆盖文件。等我确认后再按方案整理，并报告完成情况。' },
  { icon: ArrowLeftRight, title: '跨应用处理', description: '从一个应用查找并整理到另一个', prompt: '请帮助我在已授权的应用之间完成任务。先确认目标应用和内容，逐步观察并操作；发送、发布或提交前暂停，等我确认。' },
  { icon: ScanText, title: '总结当前页面', description: '读取当前页面并提炼重点', prompt: '请观察当前已授权应用的页面，概括主要信息；如果页面读不到，请明确说明，不要猜测。' },
] as const

export function TaskStarter() {
  useLanguage()
  const [selected, setSelected] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [copyStatus, setCopyStatus] = useState('')
  const [inserting, setInserting] = useState(false)
  const draftRegion = useRef<HTMLDivElement | null>(null)
  useEffect(() => { if (selected !== null) draftRegion.current?.scrollIntoView?.({ block: 'nearest' }) }, [selected])
  const insertDraft = async () => {
    setInserting(true); setCopyStatus('')
    try { await runtimeBridge.insertTaskDraft(draft); setCopyStatus('已请求放入对话，请核对草稿') }
    catch { setCopyStatus('对话不可用，请复制任务后粘贴') }
    finally { setInserting(false) }
  }
  const copyDraft = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
      await navigator.clipboard.writeText(draft)
      setCopyStatus('已复制，可以粘贴到对话中')
    } catch {
      setCopyStatus('复制不可用，请长按文本手动复制')
    }
  }
  return <section className="task-starter" aria-labelledby="task-starter-title">
    <div className="task-starter-heading"><div><span>QUICK START</span><h2 id="task-starter-title">{t('试试这些任务')}</h2></div><p>{t('选择一个示例，补充细节后发给助手')}</p></div>
    <div className="task-starter-grid">{tasks.map((task, index) => {
      const Icon = task.icon
      return <button type="button" key={task.title} disabled={inserting} className={`task-starter-card ${selected === index ? 'selected' : ''}`} aria-pressed={selected === index} onClick={() => { setSelected(index); setDraft(t(task.prompt)); setCopyStatus('') }}><span className="task-starter-icon" aria-hidden="true"><Icon size={18} strokeWidth={1.8} /></span><strong>{t(task.title)}</strong><small>{t(task.description)}</small></button>
    })}</div>
    {selected !== null && <div ref={draftRegion} className="task-starter-draft"><label htmlFor="task-starter-prompt">{t('任务示例：编辑后放入对话')}</label><textarea id="task-starter-prompt" value={draft} maxLength={2000} disabled={inserting} onChange={event => setDraft(event.target.value)} rows={4} /><div className="task-starter-actions"><button className="button button-primary compact-button" type="button" disabled={inserting || draft.trim() === ''} onClick={() => { void insertDraft() }}>{t('放入对话草稿')}</button><button className="button button-secondary compact-button" type="button" disabled={inserting || draft.trim() === ''} onClick={() => { void copyDraft() }}>{t('复制任务')}</button><span role="status">{t(copyStatus)}</span></div><small>{t('仅放入草稿，不会自动发送。')}</small></div>}
  </section>
}
