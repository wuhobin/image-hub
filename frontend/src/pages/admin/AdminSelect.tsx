import {useEffect, useRef, useState} from 'react'
import {CaretDown} from '@phosphor-icons/react/dist/csr/CaretDown'

type Option = { value: string; label: string; description?: string }
type Props = {
    id: string
    label: string
    options: Option[]
    disabled?: boolean
} & ({ multiple: true; value: string[]; onChange: (value: string[]) => void }
    | { multiple?: false; value: string; onChange: (value: string) => void })

/** 后台共用原生浮层；复选框支持多选，单选框保留方向键操作。 */
export function AdminSelect(props: Props) {
    const {id, label, options, disabled} = props
    const trigger = useRef<HTMLButtonElement>(null)
    const panel = useRef<HTMLDivElement>(null)
    const [open, setOpen] = useState(false)
    const selected = props.multiple ? props.value : props.value ? [props.value] : []
    const allSelected = options.length > 0 && options.every(option => selected.includes(option.value))
    const current = options.find(option => option.value === props.value)
    const summary = props.multiple ? selected.length ? '已选择 ' + selected.length + ' 项' : '请选择'
        : current ? current.label + (current.description ? ' · ' + current.description : '') : '请先选择允许的选项'

    function positionPanel() {
        if (!trigger.current || !panel.current) return
        const rect = trigger.current.getBoundingClientRect()
        const below = window.innerHeight - rect.bottom - 16
        const above = rect.top - 16
        const down = below >= 260 || below >= above
        panel.current.style.width = Math.min(Math.max(rect.width, 240), window.innerWidth - 24) + 'px'
        panel.current.style.maxHeight = Math.max(100, down ? below : above) + 'px'
        panel.current.style.left = Math.max(12, Math.min(rect.left, window.innerWidth - panel.current.offsetWidth - 12)) + 'px'
        panel.current.style.top = Math.max(12, down ? rect.bottom + 6 : rect.top - panel.current.offsetHeight - 6) + 'px'
    }

    useEffect(() => {
        if (!open) return
        if (disabled) {
            panel.current?.hidePopover();
            return
        }
        window.addEventListener('resize', positionPanel)
        window.addEventListener('scroll', positionPanel, true)
        return () => {
            window.removeEventListener('resize', positionPanel)
            window.removeEventListener('scroll', positionPanel, true)
        }
    }, [open, disabled])

    function close() {
        panel.current?.hidePopover()
        trigger.current?.focus({preventScroll: true})
    }

    return <>
        <button id={id} ref={trigger} type="button" className="admin-select-trigger"
                disabled={disabled || !options.length}
                aria-label={label + '，' + summary} aria-haspopup="dialog" aria-expanded={open}
                aria-controls={id + '-options'}
                onClick={() => {
                    if (!panel.current) return
                    if (panel.current.matches(':popover-open')) {
                        close();
                        return
                    }
                    // 先显示并定位，再聚焦，避免浏览器把表单卷动到浮层的原始位置。
                    panel.current.showPopover()
                    positionPanel()
                    const input = panel.current.querySelector<HTMLInputElement>('input:checked') || panel.current.querySelector<HTMLInputElement>('input')
                    input?.focus({preventScroll: true})
                }}>
            <span>{summary}</span><CaretDown size={15} aria-hidden="true"/>
        </button>
        <div ref={panel} id={id + '-options'} popover="auto" role="dialog" aria-label={label}
             className="admin-select-popover" onToggle={event => setOpen(event.newState === 'open')}
             onKeyDown={event => {
                 if (event.key === 'Escape' || (event.key === 'Enter' && event.target instanceof HTMLInputElement)) {
                     event.preventDefault()
                     event.stopPropagation()
                     close()
                 }
             }}>
            <div className="admin-select-heading">
                <div><strong>{label}</strong>{props.multiple &&
                    <span aria-live="polite">{selected.length} / {options.length}</span>}</div>
                {props.multiple && <div className="admin-select-actions">
                    <button type="button" disabled={disabled || allSelected}
                            onClick={() => props.onChange(options.map(option => option.value))}>{allSelected ? '已全选' : '全选'}</button>
                    <button type="button" disabled={disabled || !selected.length}
                            onClick={() => props.onChange([])}>清空
                    </button>
                </div>}
            </div>
            <div className="admin-select-options" role={props.multiple ? 'group' : 'radiogroup'} aria-label={label}>
                {options.map(option => <label className="admin-select-option" key={option.value}>
                    <input type={props.multiple ? 'checkbox' : 'radio'} name={id} value={option.value}
                           disabled={disabled}
                           checked={selected.includes(option.value)} onChange={event => {
                        if (props.multiple) props.onChange(event.target.checked ? [...selected, option.value] : selected.filter(value => value !== option.value))
                        else props.onChange(option.value)
                    }} onClick={event => {
                        if (!props.multiple && event.detail > 0) close()
                    }}/>
                    <span className="admin-select-copy"><span>{option.label}</span>{option.description &&
                        <small>{option.description}</small>}</span>
                </label>)}
            </div>
            {props.multiple && <div className="admin-select-footer"><span>可选择多项</span>
                <button type="button" className="admin-select-done" onClick={close}>完成选择</button>
            </div>}
        </div>
    </>
}
