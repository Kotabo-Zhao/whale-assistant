import { useState } from 'react'
import { t, useLanguage } from '../i18n'
import './TaskStarter.css'

const tasks = [
  { title: '查找信息', description: '打开应用，查找并整理结果', prompt: '请打开我指定的应用，查找我需要的信息并整理结果。每次操作后重新观察界面；遇到登录、支付或需要确认的步骤时停下并告诉我。' },
  { title: '整理文件', description: '先列清单，再按要求整理', prompt: '请先列出我指定位置的文件并提出整理方案，不要直接删除或覆盖文件。等我确认后再按方案整理，并报告完成情况。' },
  { title: '跨应用处理', description: '从一个应用查找并整理到另一个', prompt: '请帮助我在已授权的应用之间完成任务。先确认目标应用和内容，逐步观察并操作；发送、发布或提交前暂停，等我确认。' },
  { title: '总结当前页面', description: '读取当前页面并提炼重点', prompt: '请观察当前已授权应用的页面，概括主要信息；如果页面读不到，请明确说明，不要猜测。' },
] as const

export function TaskStarter() {
  useLanguage()
  const [selected, setSelected] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [copyStatus, setCopyStatus] = useState('')
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
    <div className="task-starter-grid">{tasks.map((task, index) => <button type="button" key={task.title} className={`task-starter-card ${selected === index ? 'selected' : ''}`} aria-pressed={selected === index} onClick={() => { setSelected(index); setDraft(t(task.prompt)); setCopyStatus('') }}><strong>{t(task.title)}</strong><small>{t(task.description)}</small></button>)}</div>
    {selected !== null && <div className="task-starter-draft"><label htmlFor="task-starter-prompt">{t('任务示例：可编辑后复制到对话')}</label><textarea id="task-starter-prompt" value={draft} onChange={event => setDraft(event.target.value)} rows={4} /><div><button className="button button-secondary compact-button" type="button" disabled={draft.trim() === ''} onClick={() => { void copyDraft() }}>{t('复制任务')}</button> <span role="status">{t(copyStatus)}</span></div></div>}
  </section>
}
