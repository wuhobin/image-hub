import { useEffect, useId, useRef, useState } from 'react'
import {GEMINI_IMAGE_RESOLUTIONS, GENERATION_RESOLUTIONS, generationResolution} from '../lib/rules'

/** 按模型支持的比例选图；原生 popover 负责点击外部和 Escape 关闭。 */
export function CreationRatioPicker({ value, options, size, sizes, disabled, onChange, onSizeChange }: {
  value: string
  options: string[]
  size: string
  sizes: string[]
  disabled: boolean
  onChange: (value: string) => void
  onSizeChange: (value: string) => void
}) {
  const id = useId()
  const trigger = useRef<HTMLButtonElement>(null)
  const panel = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
    const resolutions = sizes.some(item => generationResolution(item) === '512') ? GEMINI_IMAGE_RESOLUTIONS : GENERATION_RESOLUTIONS

  function positionPanel() {
    if (!trigger.current || !panel.current) return
    const rect = trigger.current.getBoundingClientRect()
    const { offsetWidth: width, offsetHeight: height } = panel.current
    const left = Math.max(12, Math.min(rect.left - 12, window.innerWidth - width - 12))
    const top = rect.top >= height + 20 ? rect.top - height - 8 : Math.min(rect.bottom + 8, window.innerHeight - height - 12)
    panel.current.style.left = left + 'px'
    panel.current.style.top = Math.max(12, top) + 'px'
  }

  useEffect(() => {
    if (!open) return
    if (disabled) { panel.current?.hidePopover(); return }
    window.addEventListener('resize', positionPanel)
    window.addEventListener('scroll', positionPanel, true)
    return () => {
      window.removeEventListener('resize', positionPanel)
      window.removeEventListener('scroll', positionPanel, true)
    }
  }, [open, disabled])

  function toggle() {
    if (!panel.current) return
    if (panel.current.matches(':popover-open')) { panel.current.hidePopover(); return }
    panel.current.showPopover()
    positionPanel()
    panel.current.querySelector<HTMLInputElement>('input:checked')?.focus({ preventScroll: true })
  }

  // 以最长边为基准，横竖画幅均完整放入预览区域。
  function frameStyle(ratio: string) {
    const [width, height] = ratio.split(':').map(Number)
    const aspect = width / height || 1
    return { width: Math.min(aspect, 1) * 100 + '%', height: Math.min(1 / aspect, 1) * 100 + '%' }
  }

  return <div className="creation-ratio-picker">
    <button type="button" id="creation-size" ref={trigger} className="creation-ratio-trigger"
      disabled={disabled} aria-label={value ? '画面比例 ' + value + '，分辨率 ' + (generationResolution(size) || size) : '画面比例'} aria-haspopup="dialog"
      aria-expanded={open} aria-controls={id} onClick={toggle}>
      <span className="creation-ratio-icon" aria-hidden="true"><i style={frameStyle(value || '1:1')} /></span>
      <span>{value ? value + ' · ' + (generationResolution(size) || '自定义') : '比例'}</span>
    </button>
    <div ref={panel} id={id} popover="auto" role="dialog" aria-label="选择比例与分辨率"
      className="creation-ratio-popover" onToggle={event => setOpen(event.newState === 'open')}
      onKeyDown={event => {
        if (event.key !== 'Enter') return
        event.preventDefault()
        event.currentTarget.hidePopover()
        trigger.current?.focus()
      }}>
      <div className="creation-ratio-layout">
        <div className="creation-ratio-grid" role="radiogroup" aria-label="画面比例">
          {options.map(option => <label key={option} className="creation-ratio-option">
            <input type="radio" name={id} value={option} checked={value === option} disabled={disabled}
              onChange={() => onChange(option)} aria-label={option} />
              <span className="creation-ratio-symbol" aria-hidden="true"><span style={frameStyle(option)}/></span>
              <span className="creation-ratio-label" aria-hidden="true">{option}</span>
          </label>)}
        </div>
        <div className="creation-ratio-preview" role="img" aria-label={'画幅预览 ' + value}>
          <div className="creation-ratio-frame" style={frameStyle(value || '1:1')} />
        </div>
      </div>
      <section className="creation-resolution-panel" aria-label="分辨率">
        <div className="creation-resolution-heading"><span>分辨率</span><span>{size.replace('x', '×')}</span></div>
          <div id="creation-resolution" className="creation-resolution-tiers" role="radiogroup" aria-label="分辨率"
               style={{gridTemplateColumns: `repeat(${resolutions.length}, minmax(0, 1fr))`}}>
              {resolutions.map(tier => {
            const candidate = sizes.find(item => generationResolution(item) === tier)
            return <label key={tier} className="creation-resolution-tier" title={candidate ? candidate.replace('x', '×') : '当前比例不可用'}>
              <input type="radio" name={id + '-resolution'} value={candidate || tier} checked={!!candidate && size === candidate}
                disabled={disabled || !candidate} onChange={() => candidate && onSizeChange(candidate)} aria-label={tier} />
              <span>{tier}</span>
            </label>
          })}
          {sizes.filter(item => !generationResolution(item)).map(candidate => <label key={candidate} className="creation-resolution-tier is-custom">
            <input type="radio" name={id + '-resolution'} value={candidate} checked={size === candidate}
              disabled={disabled} onChange={() => onSizeChange(candidate)} aria-label={'自定义 ' + candidate.replace('x', '×')} />
            <span>自定义 · {candidate.replace('x', '×')}</span>
          </label>)}
        </div>
      </section>
    </div>
  </div>
}
