import { useLayoutEffect, useRef, useState } from 'react'
import { Minus } from '@phosphor-icons/react/dist/csr/Minus'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { CornersOut } from '@phosphor-icons/react/dist/csr/CornersOut'
import { DownloadSimple } from '@phosphor-icons/react/dist/csr/DownloadSimple'
import type { AppState } from '../App'
import { formatSize } from '../lib/rules'
import { Modal } from './Modal'

/** 共用大图预览；百分比以原图像素为准，放大后通过原生滚动区域拖动查看。 */
export function ImagePreview({ image, onClose }: { image: NonNullable<AppState['preview']>; onClose: () => void }) {
  const viewport = useRef<HTMLDivElement>(null)
  const drag = useRef<{ id: number; x: number; y: number; left: number; top: number } | null>(null)
  const [bounds, setBounds] = useState({ width: 0, height: 0 })
  const [dimensions, setDimensions] = useState({ width: image.width, height: image.height })
  const [zoom, setZoom] = useState<number | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [failed, setFailed] = useState(false)
  const fit = Math.min(1, bounds.width / Math.max(1, dimensions.width), bounds.height / Math.max(1, dimensions.height))
  const scale = zoom ?? fit
  const minScale = Math.min(.25, fit)
  const width = dimensions.width * scale
  const height = dimensions.height * scale
  const canPan = width > bounds.width + 1 || height > bounds.height + 1
  const source = image.url ?? image.preview
  const downloadUrl = new URL(source, window.location.href)
  // 云端图片沿用七牛附件下载；本地 blob/data 预览直接使用 download。
  if (downloadUrl.protocol === 'https:' || downloadUrl.protocol === 'http:') downloadUrl.searchParams.set('attname', image.name)

  useLayoutEffect(() => {
    const element = viewport.current!
    const observer = new ResizeObserver(() => setBounds({ width: element.clientWidth, height: element.clientHeight }))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  useLayoutEffect(() => {
    const element = viewport.current!
    element.scrollLeft = (element.scrollWidth - element.clientWidth) / 2
    element.scrollTop = (element.scrollHeight - element.clientHeight) / 2
  }, [scale, bounds.width, bounds.height])

  return <Modal title={image.name} onClose={onClose} className="preview-modal">
    <div ref={viewport} className={'preview-image-wrap' + (canPan ? ' is-zoomed' : '')}
      tabIndex={0} role="region" aria-label="图片预览，放大后可拖动或使用方向键查看"
      onClick={event => {
        if (drag.current) { drag.current = null; return }
        if (event.target === event.currentTarget || (event.target as HTMLElement).classList.contains('preview-image-canvas')) onClose()
      }}
      onPointerDown={event => {
        drag.current = null
        if (!canPan || event.button !== 0) return
        drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY, left: event.currentTarget.scrollLeft, top: event.currentTarget.scrollTop }
        event.currentTarget.setPointerCapture(event.pointerId)
      }}
      onPointerMove={event => {
        const start = drag.current
        if (!start || start.id !== event.pointerId || !event.currentTarget.hasPointerCapture(event.pointerId)) return
        const dx = event.clientX - start.x, dy = event.clientY - start.y
        event.currentTarget.scrollLeft = start.left - dx
        event.currentTarget.scrollTop = start.top - dy
      }}
      onPointerUp={event => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId)
      }}
      onPointerCancel={() => { drag.current = null }}>
      <div className="preview-image-canvas" style={{ width, height }}>
        <img src={source} alt={image.name} draggable={false} style={{ width, height, visibility: loaded ? 'visible' : 'hidden' }}
          onLoad={event => {
            setDimensions({ width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight })
            setLoaded(true)
          }}
          onError={() => setFailed(true)} />
      </div>
      {!loaded && <p className="preview-image-status" role="status">{failed ? '图片加载失败，请关闭后重试' : '正在加载图片…'}</p>}
    </div>
    <footer className="preview-controls">
      <div className="preview-toolbar" role="group" aria-label="图片预览工具">
        <button type="button" aria-label="缩小" title="缩小" disabled={!loaded || scale <= minScale} onClick={() => setZoom(Math.max(minScale, scale - .25))}><Minus size={19} /></button>
        <output aria-label="当前缩放比例" aria-live="polite">{Math.round(scale * 100)}%</output>
        <button type="button" aria-label="放大" title="放大" disabled={!loaded || scale >= 4} onClick={() => setZoom(Math.min(4, scale + .25))}><Plus size={19} /></button>
        <span className="preview-tool-divider" aria-hidden="true" />
        <button type="button" aria-label="适应窗口" title="适应窗口" disabled={!loaded} onClick={() => {
          setZoom(null)
          viewport.current?.scrollTo(0, 0)
        }}><CornersOut size={19} /></button>
        <a href={downloadUrl.href} download={image.name} aria-label="下载图片" title="下载图片"><DownloadSimple size={19} /></a>
      </div>
      <div className="preview-caption"><span>{dimensions.width} × {dimensions.height}</span><span>{formatSize(image.size)}</span></div>
    </footer>
  </Modal>
}
