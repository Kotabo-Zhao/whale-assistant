import { useEffect, useRef, type ReactNode } from 'react'

/** 阅读和详情共享一个可返回、可键盘操作的阅读层。 */
export function KnowledgeDialog({ titleId, onClose, children, pageKey }: {
  titleId: string; onClose: () => void; children: ReactNode; pageKey?: number
}) {
  const page = useRef<HTMLElement>(null)
  useEffect(() => {
    const previous = document.activeElement
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    page.current?.querySelector<HTMLButtonElement>('button')?.focus()
    return () => {
      document.body.style.overflow = overflow
      if (previous instanceof HTMLElement && previous.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => { page.current?.scrollTo?.(0, 0) }, [pageKey])
  return <div className="knowledge-detail-backdrop" onKeyDown={event => {
    if (event.key === 'Escape') { event.stopPropagation(); onClose() }
    if (event.key !== 'Tab') return
    const controls = Array.from(page.current?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [tabindex="0"]') ?? [])
      .filter(element => !element.closest('[hidden], [inert]'))
    const first = controls[0]; const last = controls[controls.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }}>
    <section ref={page} className="knowledge-detail-page capture-reader" role="dialog" aria-modal="true" aria-labelledby={titleId}>{children}</section>
  </div>
}
