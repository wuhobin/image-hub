import { memo, useEffect, useRef, useState } from 'react'
import type { ComponentType } from 'react'
import type { StarfieldProps } from './Starfield'

/** 首屏内容先绘制，仅在装饰区域可见且允许动效时，空闲加载 WebGL。加载失败保留 CSS 背景。 */
export const Ambient = memo(function Ambient(props: StarfieldProps) {
  const host = useRef<HTMLDivElement>(null)
  const [Starfield, setStarfield] = useState<ComponentType<StarfieldProps> | null>(null)

  useEffect(() => {
    if (Starfield) return
    const element = host.current
    if (!element) return
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection
    let visible = false
    let disposed = false
    let loading = false
    let frame = 0
    let idle: number | undefined
    let timer: number | undefined
    const cancel = () => {
      cancelAnimationFrame(frame)
      if (idle !== undefined) window.cancelIdleCallback(idle)
      clearTimeout(timer)
      idle = timer = undefined
    }
    const allowed = () => visible && !document.hidden && !reducedMotion.matches && !connection?.saveData
    const load = () => {
      if (disposed || loading || !allowed()) return
      loading = true
      void import('./Starfield').then(module => {
        loading = false
        if (!disposed && allowed()) setStarfield(() => module.default)
      }).catch(() => { /* 装饰依赖不可用时不影响选图、登录等业务。 */ })
    }
    const schedule = () => {
      cancel()
      if (!allowed() || loading) return
      // 双 RAF 跨过首屏绘制；后台标签和隐藏的移动端登录配图均不抢占资源。
      frame = requestAnimationFrame(() => {
        frame = requestAnimationFrame(() => {
          if (typeof window.requestIdleCallback === 'function') idle = window.requestIdleCallback(load, { timeout: 2000 })
          else timer = window.setTimeout(load, 200)
        })
      })
    }
    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
      schedule()
    })
    observer.observe(element)
    document.addEventListener('visibilitychange', schedule)
    reducedMotion.addEventListener('change', schedule)
    return () => {
      disposed = true
      cancel()
      observer.disconnect()
      document.removeEventListener('visibilitychange', schedule)
      reducedMotion.removeEventListener('change', schedule)
    }
  }, [Starfield])

  return <div ref={host} className="starfield ambient-layer" aria-hidden="true">
    {Starfield && <Starfield {...props} />}
  </div>
})
