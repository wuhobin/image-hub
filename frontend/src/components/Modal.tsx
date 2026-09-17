import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { X } from '@phosphor-icons/react/dist/csr/X'

export function Modal({ title, onClose, children, className = '' }: { title: string; onClose: () => void; children: ReactNode; className?: string }) {
  const ref = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const dialog = ref.current!
    const previousFocus = document.activeElement
    const previousOverflow = document.body.style.overflow
    dialog.showModal()
    document.body.style.overflow = 'hidden'
    return () => {
      dialog.close()
      document.body.style.overflow = previousOverflow
      // 等待 dialog 移除后再恢复；复用描述等操作已主动转移焦点时不覆盖。
      requestAnimationFrame(() => {
        if (document.activeElement === document.body && previousFocus instanceof HTMLElement && previousFocus.isConnected) {
          previousFocus.focus({ preventScroll: true })
        }
      })
    }
  }, [])
  return <dialog ref={ref} className={`modal ${className}`} onCancel={onClose}
    onClick={event => { if (event.target === event.currentTarget) onClose() }} aria-labelledby="modal-title">
    <div className="modal-inner">
      <div className="modal-header"><h2 id="modal-title">{title}</h2><button className="icon-button" onClick={onClose} aria-label="关闭弹窗"><X size={21} /></button></div>
      {children}
    </div>
  </dialog>
}

