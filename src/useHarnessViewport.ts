import { useLayoutEffect } from 'react'
import { Capacitor } from '@capacitor/core'
import { runtimeBridge } from './platform/native'

/** Keep the single App navigation visible around the isolated conversation WebView. */
export function useHarnessViewport(view: string, covered: boolean): void {
  useLayoutEffect(() => {
    if (!Capacitor.isNativePlatform() || !runtimeBridge.setHarnessViewport) return
    const host = document.querySelector<HTMLElement>('.app-main')
    if (!host) return
    let frame = 0, second = 0, closed = false
    const publish = () => {
      cancelAnimationFrame(frame); cancelAnimationFrame(second)
      frame = requestAnimationFrame(() => {
        second = requestAnimationFrame(() => {
          if (closed) return
          const box = host.getBoundingClientRect()
          void runtimeBridge.setHarnessViewport?.({ view, visible: view === 'harness' && !covered,
            x: box.x, y: box.y, width: box.width, height: box.height, viewportWidth: window.innerWidth }).catch(() => undefined)
        })
      })
    }
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(publish)
    observer?.observe(host)
    window.addEventListener('resize',publish)
    window.visualViewport?.addEventListener('resize',publish)
    publish()
    return () => {
      closed = true; cancelAnimationFrame(frame); cancelAnimationFrame(second)
      observer?.disconnect(); window.removeEventListener('resize',publish)
      window.visualViewport?.removeEventListener('resize',publish)
    }
  }, [view,covered])
}
