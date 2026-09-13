import { useLayoutEffect, useRef, useState } from 'react'
import { CheckCircle } from '@phosphor-icons/react/dist/csr/CheckCircle'
import { WarningCircle } from '@phosphor-icons/react/dist/csr/WarningCircle'
import { X } from '@phosphor-icons/react/dist/csr/X'

type Notice = { text: string; error: boolean }
type NoticeToastProps = { notice: Notice | null; onDismiss: () => void }

/** 保留退出中的提示，动画结束后再移除；新消息到来时取消旧动画。 */
export function NoticeToast({ notice, onDismiss }: NoticeToastProps) {
  const container = useRef<HTMLDivElement>(null)
  const [displayed, setDisplayed] = useState(notice)
  if (notice && notice !== displayed) setDisplayed(notice)

  useLayoutEffect(() => {
    const toast = container.current?.querySelector<HTMLElement>('.toast')
    if (!toast || !displayed) return
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
    let animations: Animation[] = []
    const finishExit = () => setDisplayed(current => current === displayed ? null : current)
    const animate = () => {
      animations.forEach(animation => animation.cancel())
      animations = []
      if (reducedMotion.matches) {
        if (!notice) finishExit()
        return
      }
      // 提示保留原有进退场反馈，但不再让全站为简单补间提前下载 GSAP。
      const animation = toast.animate(notice
        ? [{ opacity: 0, transform: 'translateY(-14px) scale(.97)' }, { opacity: 1, transform: 'translateY(0) scale(1)' }]
        : [{ opacity: 1, transform: 'translateY(0)' }, { opacity: 0, transform: 'translateY(-10px)' }],
      { duration: notice ? 380 : 200, easing: notice ? 'cubic-bezier(.16,1,.3,1)' : 'ease-in', fill: 'both' })
      animations.push(animation)
      if (!notice) void animation.finished.then(finishExit).catch(() => { /* 新提示到来时取消旧退出动画。 */ })
      const icon = toast.querySelector('.toast-icon')
      if (notice && icon) animations.push(icon.animate(
        [{ transform: 'scale(.7)' }, { transform: 'scale(1)' }],
        { duration: 400, delay: 80, easing: 'cubic-bezier(.34,1.56,.64,1)', fill: 'both' }))
    }
    animate()
    reducedMotion.addEventListener('change', animate)
    return () => {
      animations.forEach(animation => animation.cancel())
      reducedMotion.removeEventListener('change', animate)
    }
  }, [notice, displayed])

  return <div ref={container} className="toast-container" aria-live="polite" aria-atomic="true">
    {displayed && <div className={`toast ${displayed.error ? 'toast-error' : ''}`}>
      <span className="toast-icon" aria-hidden="true">
        {displayed.error ? <WarningCircle size={21} /> : <CheckCircle size={21} weight="fill" />}
      </span>
      <span className="toast-message">{displayed.text}</span>
      <button type="button" className="icon-button" aria-label="关闭提示" onClick={onDismiss}><X size={16} /></button>
    </div>}
  </div>
}
