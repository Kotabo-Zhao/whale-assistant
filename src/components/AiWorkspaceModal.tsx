import { useCallback, useEffect, useState } from 'react'
import { Download, ExternalLink, Eye, FolderOpen, RefreshCw, Share2, Trash2, X } from 'lucide-react'
import { t } from '../i18n'
import type { RuntimeBridge } from '../platform/types'

interface WorkspaceFileItem {
  project: string
  path: string
  fileName: string
  ext: string
  bytes: number
  modifiedAt: number
  isImage: boolean
}

interface PreviewState {
  fileName: string
  isImage: boolean
  dataUrl?: string
  textPreview?: string
  truncated?: boolean
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function fmtDate(ms: number): string {
  if (!ms) return ''
  const d = new Date(ms)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${mm}-${dd} ${hh}:${mi}`
}

/**
 * AI 工作目录管理器（whale-projects/）：
 * 支持按项目筛选、关键词搜索、应用内图片/文本直接预览、导出到手机下载目录、系统分享与安全删除。
 */
export function AiWorkspaceModal({ bridge, onClose }: { bridge: RuntimeBridge; onClose: () => void }) {
  const [projects, setProjects] = useState<string[]>([])
  const [activeProject, setActiveProject] = useState('')
  const [files, setFiles] = useState<WorkspaceFileItem[]>([])
  const [totalBytes, setTotalBytes] = useState(0)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [toast, setToast] = useState('')
  const [preview, setPreview] = useState<PreviewState | null>(null)

  const loadFiles = useCallback(async (proj: string) => {
    if (!bridge.companionDashboard) {
      setLoading(false)
      return
    }
    setLoading(true)
    try {
      const res = await bridge.companionDashboard({
        operation: 'workspaceList',
        project: proj,
      }) as { projects?: unknown[]; files?: unknown[]; totalBytes?: number } | undefined
      if (Array.isArray(res?.projects)) {
        setProjects(res.projects.filter((p): p is string => typeof p === 'string'))
      }
      if (Array.isArray(res?.files)) {
        const list = (res.files as Array<Record<string, unknown>>).map(f => ({
          project: typeof f.project === 'string' ? f.project : 'scratch',
          path: typeof f.path === 'string' ? f.path : '',
          fileName: typeof f.fileName === 'string' ? f.fileName : '',
          ext: typeof f.ext === 'string' ? f.ext : '',
          bytes: typeof f.bytes === 'number' ? f.bytes : 0,
          modifiedAt: typeof f.modifiedAt === 'number' ? f.modifiedAt : 0,
          isImage: Boolean(f.isImage),
        })).filter(f => f.fileName)
        setFiles(list)
      }
      setTotalBytes(typeof res?.totalBytes === 'number' ? res.totalBytes : 0)
    } catch {
      setToast(t('读取 AI 工作目录失败'))
    } finally {
      setLoading(false)
    }
  }, [bridge])

  useEffect(() => {
    void loadFiles(activeProject)
  }, [activeProject, loadFiles])

  const showNotice = (msg: string) => {
    setToast(msg)
    window.setTimeout(() => setToast(prev => (prev === msg ? '' : prev)), 2200)
  }

  const handlePreview = async (item: WorkspaceFileItem) => {
    if (!bridge.companionDashboard) return
    try {
      const res = await bridge.companionDashboard({
        operation: 'workspacePreview',
        project: item.project,
        path: item.path,
      }) as { ok?: boolean; isImage?: boolean; canTextPreview?: boolean; dataUrl?: string; textPreview?: string; truncated?: boolean } | undefined
      if (res?.ok && res.isImage && res.dataUrl) {
        setPreview({ fileName: item.fileName, isImage: true, dataUrl: res.dataUrl })
      } else if (res?.ok && res.canTextPreview && typeof res.textPreview === 'string') {
        setPreview({ fileName: item.fileName, isImage: false, textPreview: res.textPreview, truncated: Boolean(res.truncated) })
      } else {
        await bridge.companionDashboard({ operation: 'openExportedFile', fileName: item.fileName })
      }
    } catch {
      showNotice(t('无法预览该文件'))
    }
  }

  const handleExport = async (item: WorkspaceFileItem, share: boolean) => {
    if (!bridge.companionDashboard) return
    try {
      await bridge.companionDashboard({
        operation: 'workspaceExport',
        project: item.project,
        path: item.path,
        share,
      })
      showNotice(share ? t('已调起系统分享') : t('已保存到「下载/WhaleAssistant」'))
    } catch {
      showNotice(t('导出失败'))
    }
  }

  const handleDelete = async (item: WorkspaceFileItem) => {
    if (!bridge.companionDashboard) return
    try {
      await bridge.companionDashboard({
        operation: 'workspaceDelete',
        project: item.project,
        path: item.path,
      })
      setFiles(prev => prev.filter(f => !(f.project === item.project && f.path === item.path)))
      showNotice(t('已删除 {0}', item.fileName))
    } catch {
      showNotice(t('删除失败'))
    }
  }

  const filtered = query.trim()
    ? files.filter(f => f.fileName.toLowerCase().includes(query.trim().toLowerCase()) || f.path.toLowerCase().includes(query.trim().toLowerCase()))
    : files

  return (
    <div className="whale-lightbox-backdrop" role="dialog" aria-modal="true" aria-label={t('AI 工作目录')} onClick={onClose}>
      <div className="whale-workspace-modal" onClick={e => e.stopPropagation()}>
        <div className="whale-workspace-head">
          <div className="whale-workspace-title">
            <FolderOpen size={18} />
            <strong>{t('AI 工作目录')}</strong>
            <small>{files.length} {t('个文件')} · {fmtSize(totalBytes)}</small>
          </div>
          <div className="whale-workspace-head-btns">
            <button type="button" onClick={() => { void loadFiles(activeProject) }} aria-label={t('刷新')}>
              <RefreshCw size={15} />
            </button>
            <button type="button" onClick={onClose} aria-label={t('关闭')}>
              <X size={18} />
            </button>
          </div>
        </div>

        <div className="whale-workspace-filters">
          <button
            type="button"
            className={activeProject === '' ? 'is-active' : ''}
            onClick={() => setActiveProject('')}
          >
            {t('全部')}
          </button>
          {projects.map(p => (
            <button
              key={p}
              type="button"
              className={activeProject === p ? 'is-active' : ''}
              onClick={() => setActiveProject(p)}
            >
              {p}
            </button>
          ))}
        </div>

        <div className="whale-workspace-search">
          <input
            type="search"
            value={query}
            placeholder={t('按文件名筛选…')}
            onChange={e => setQuery(e.target.value)}
          />
        </div>

        {toast && <div className="whale-workspace-toast">{toast}</div>}

        <div className="whale-workspace-list">
          {loading ? (
            <p className="whale-workspace-empty">{t('正在读取工作目录…')}</p>
          ) : filtered.length === 0 ? (
            <p className="whale-workspace-empty">{t('当前目录暂无文件，AI 生成的文档、图片、代码和上传附件都会保存在这里。')}</p>
          ) : (
            filtered.map(item => (
              <div key={`${item.project}/${item.path}`} className="whale-workspace-row">
                <div className="whale-workspace-row-info" onClick={() => { void handlePreview(item) }}>
                  <span className="whale-workspace-ext">{(item.ext || 'FILE').slice(0, 4).toUpperCase()}</span>
                  <div className="whale-workspace-row-text">
                    <strong>{item.fileName}</strong>
                    <small>{item.project}/{item.path} · {fmtSize(item.bytes)} · {fmtDate(item.modifiedAt)}</small>
                  </div>
                </div>
                <div className="whale-workspace-row-actions">
                  <button type="button" title={t('预览')} aria-label={t('预览')} onClick={() => { void handlePreview(item) }}>
                    <Eye size={14} />
                  </button>
                  <button type="button" title={t('保存到下载')} aria-label={t('保存到下载')} onClick={() => { void handleExport(item, false) }}>
                    <Download size={14} />
                  </button>
                  <button type="button" title={t('分享')} aria-label={t('分享')} onClick={() => { void handleExport(item, true) }}>
                    <Share2 size={14} />
                  </button>
                  <button type="button" className="is-danger" title={t('删除')} aria-label={t('删除')} onClick={() => { void handleDelete(item) }}>
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {preview && (
        <div className="whale-lightbox-backdrop" onClick={() => setPreview(null)}>
          <div className={`whale-lightbox-sheet${preview.isImage ? '' : ' is-doc'}`} onClick={e => e.stopPropagation()}>
            <div className="whale-lightbox-top">
              <span>{preview.fileName}</span>
              <div className="whale-lightbox-actions">
                <button
                  type="button"
                  onClick={() => {
                    void bridge.companionDashboard?.({ operation: 'openExportedFile', fileName: preview.fileName }).catch(() => {})
                  }}
                >
                  <ExternalLink size={14} />{t('外部打开')}
                </button>
                <button type="button" onClick={() => setPreview(null)} aria-label={t('关闭')}><X size={17} /></button>
              </div>
            </div>
            {preview.isImage && preview.dataUrl ? (
              <div className="whale-lightbox-body">
                <img src={preview.dataUrl} alt={preview.fileName} />
              </div>
            ) : (
              <pre className="whale-doc-preview-body">
                {preview.textPreview}
                {preview.truncated ? `\n\n…（文件较大，仅展示前 24KB）` : ''}
              </pre>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
