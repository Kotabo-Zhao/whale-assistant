import { useEffect, useState } from 'react'
import { ExternalLink, Eye, Share2, X } from 'lucide-react'
import { t } from '../i18n'
import type { RuntimeBridge } from '../platform/types'

interface PreviewPayload {
  ok?: boolean
  fileName?: string
  ext?: string
  bytes?: number
  isImage?: boolean
  canTextPreview?: boolean
  dataUrl?: string
  textPreview?: string
  truncated?: boolean
}

const previewCache = new Map<string, PreviewPayload>()

function formatFileSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`
}

function badgeMeta(ext: string): { label: string; bg: string; fg: string } {
  const e = ext.toLowerCase()
  if (e === 'pdf') return { label: 'PDF', bg: '#ef4444', fg: '#ffffff' }
  if (e === 'ppt' || e === 'pptx') return { label: 'PPT', bg: '#f97316', fg: '#ffffff' }
  if (e === 'doc' || e === 'docx') return { label: 'DOC', bg: '#2563eb', fg: '#ffffff' }
  if (e === 'xls' || e === 'xlsx' || e === 'csv') return { label: 'XLS', bg: '#16a34a', fg: '#ffffff' }
  if (e === 'zip' || e === 'tar' || e === 'gz') return { label: 'ZIP', bg: '#8b5cf6', fg: '#ffffff' }
  if (e === 'md' || e === 'txt') return { label: e.toUpperCase(), bg: '#0284c7', fg: '#ffffff' }
  if (['py', 'js', 'ts', 'tsx', 'kt', 'java', 'json', 'html', 'css', 'sh', 'c', 'cpp'].includes(e)) {
    return { label: e.toUpperCase(), bg: '#0f766e', fg: '#ffffff' }
  }
  return { label: (e || 'FILE').slice(0, 4).toUpperCase(), bg: '#475569', fg: '#ffffff' }
}

/**
 * 微信风格聊天内联媒体卡片：
 * - 图片（PNG/JPG/WebP）直接内联展示在对话流中，点图弹出应用内全屏灯箱查看（无需跳外部弹窗）；
 * - 文档/代码/压缩包展示仿微信文件卡片（彩色扩展名徽标 + 文件名 + 大小 + 应用内预览 / 打开 / 分享）。
 */
export function ChatMediaCard({ bridge, fileName }: { bridge: RuntimeBridge; fileName: string }) {
  const [info, setInfo] = useState<PreviewPayload | null>(() => previewCache.get(fileName) ?? null)
  const [lightboxOpen, setLightboxOpen] = useState(false)
  const [textModalOpen, setTextModalOpen] = useState(false)

  useEffect(() => {
    if (previewCache.has(fileName) || !bridge.companionDashboard) return
    let active = true
    void bridge.companionDashboard({ operation: 'workspacePreview', fileName })
      .then(raw => {
        if (!active || !raw || typeof raw !== 'object') return
        const payload = raw as PreviewPayload
        if (payload.ok) {
          previewCache.set(fileName, payload)
          setInfo(payload)
        }
      })
      .catch(() => {})
    return () => { active = false }
  }, [bridge, fileName])

  const ext = (info?.ext || fileName.split('.').pop() || '').toLowerCase()
  const badge = badgeMeta(ext)

  const handleOpen = () => {
    void bridge.companionDashboard?.({ operation: 'openExportedFile', fileName }).catch(() => {})
  }
  const handleShare = () => {
    void bridge.companionDashboard?.({ operation: 'shareExportedFile', fileName }).catch(() => {})
  }

  // 微信风格内联图片气泡
  if (info?.isImage && info.dataUrl) {
    return (
      <>
        <div className="whale-wx-image-card">
          <button
            type="button"
            className="whale-wx-image-btn"
            aria-label={t('查看大图：{0}', fileName)}
            onClick={() => setLightboxOpen(true)}
          >
            <img src={info.dataUrl} alt={fileName} className="whale-wx-image-preview" />
          </button>
          <div className="whale-wx-image-bar">
            <span className="whale-wx-image-name">{fileName}</span>
            <button type="button" onClick={handleShare} aria-label={t('分享')}>
              <Share2 size={13} />
            </button>
          </div>
        </div>
        {lightboxOpen && (
          <div className="whale-lightbox-backdrop" role="dialog" aria-modal="true" onClick={() => setLightboxOpen(false)}>
            <div className="whale-lightbox-sheet" onClick={e => e.stopPropagation()}>
              <div className="whale-lightbox-top">
                <span>{fileName}</span>
                <div className="whale-lightbox-actions">
                  <button type="button" onClick={handleShare}><Share2 size={15} />{t('分享')}</button>
                  <button type="button" onClick={handleOpen}><ExternalLink size={15} />{t('外部打开')}</button>
                  <button type="button" onClick={() => setLightboxOpen(false)} aria-label={t('关闭')}><X size={17} /></button>
                </div>
              </div>
              <div className="whale-lightbox-body">
                <img src={info.dataUrl} alt={fileName} />
              </div>
            </div>
          </div>
        )}
      </>
    )
  }

  // 微信风格富文件卡片
  return (
    <>
      <div className="whale-wx-file-card">
        <div
          className="whale-wx-file-main"
          role="button"
          tabIndex={0}
          onClick={() => {
            if (info?.canTextPreview && info.textPreview !== undefined) {
              setTextModalOpen(true)
            } else {
              handleOpen()
            }
          }}
          onKeyDown={e => {
            if (e.key === 'Enter' || e.key === ' ') {
              e.preventDefault()
              if (info?.canTextPreview && info.textPreview !== undefined) setTextModalOpen(true)
              else handleOpen()
            }
          }}
        >
          <div className="whale-wx-file-copy">
            <strong className="whale-wx-file-title">{fileName}</strong>
            <span className="whale-wx-file-meta">
              {formatFileSize(info?.bytes) || badge.label}
              {info?.canTextPreview ? ` · ${t('点击应用内预览')}` : ` · ${t('点击打开')}`}
            </span>
          </div>
          <span className="whale-wx-file-badge" style={{ background: badge.bg, color: badge.fg }}>
            {badge.label}
          </span>
        </div>
        <div className="whale-wx-file-foot">
          {info?.canTextPreview && info.textPreview !== undefined && (
            <button type="button" onClick={() => setTextModalOpen(true)}>
              <Eye size={13} />{t('预览')}
            </button>
          )}
          <button type="button" onClick={handleShare}>
            <Share2 size={13} />{t('分享')}
          </button>
          <button type="button" className="is-primary" onClick={handleOpen}>
            <ExternalLink size={13} />{t('打开')}
          </button>
        </div>
      </div>

      {textModalOpen && info?.textPreview !== undefined && (
        <div className="whale-lightbox-backdrop" role="dialog" aria-modal="true" onClick={() => setTextModalOpen(false)}>
          <div className="whale-lightbox-sheet is-doc" onClick={e => e.stopPropagation()}>
            <div className="whale-lightbox-top">
              <span>{fileName}</span>
              <div className="whale-lightbox-actions">
                <button type="button" onClick={handleShare}><Share2 size={14} />{t('分享')}</button>
                <button type="button" onClick={handleOpen}><ExternalLink size={14} />{t('外部打开')}</button>
                <button type="button" onClick={() => setTextModalOpen(false)} aria-label={t('关闭')}><X size={17} /></button>
              </div>
            </div>
            <pre className="whale-doc-preview-body">
              {info.textPreview}
              {info.truncated ? `\n\n…（文件较大，仅展示前 24KB，可点右上角外部打开查看全文）` : ''}
            </pre>
          </div>
        </div>
      )}
    </>
  )
}
