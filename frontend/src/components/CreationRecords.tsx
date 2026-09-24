import {useEffect, useId, useRef, useState} from 'react'
import {Link} from 'react-router-dom'
import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {WarningCircle} from '@phosphor-icons/react/dist/csr/WarningCircle'
import {MagnifyingGlass} from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import {CaretDown} from '@phosphor-icons/react/dist/csr/CaretDown'
import {Check} from '@phosphor-icons/react/dist/csr/Check'
import {SortAscending} from '@phosphor-icons/react/dist/csr/SortAscending'
import type {Generation, GenerationHistoryFilter, Page} from '../lib/types'
import MasonryGrid from './MasonryGrid'
import {Trash} from '@phosphor-icons/react/dist/csr/Trash'


/** 复用原生 popover 的外部点击与 Escape 关闭，单选框保留方向键操作。 */
function CreationSort({value, onChange}: {
    value: GenerationHistoryFilter['order']
    onChange: (value: GenerationHistoryFilter['order']) => void
}) {
    const id = useId()
    const trigger = useRef<HTMLButtonElement>(null)
    const panel = useRef<HTMLDivElement>(null)
    const [open, setOpen] = useState(false)

    function positionPanel() {
        if (!trigger.current || !panel.current) return
        const rect = trigger.current.getBoundingClientRect()
        const {offsetWidth: width, offsetHeight: height} = panel.current
        panel.current.style.left = Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)) + 'px'
        panel.current.style.top = Math.max(12, rect.bottom + height + 20 <= window.innerHeight ? rect.bottom + 8 : rect.top - height - 8) + 'px'
    }

    useEffect(() => {
        if (!open) return
        window.addEventListener('resize', positionPanel)
        window.addEventListener('scroll', positionPanel, true)
        return () => {
            window.removeEventListener('resize', positionPanel)
            window.removeEventListener('scroll', positionPanel, true)
        }
    }, [open])

    function close() {
        panel.current?.hidePopover()
        trigger.current?.focus({preventScroll: true})
    }

    return <>
        <button type="button" ref={trigger} className="creation-history-sort-trigger"
                aria-label="排列顺序" aria-haspopup="dialog" aria-controls={id} aria-expanded={open}
                onClick={() => {
                    if (!panel.current) return
                    if (panel.current.matches(':popover-open')) {
                        close();
                        return
                    }
                    panel.current.showPopover()
                    positionPanel()
                    panel.current.querySelector<HTMLInputElement>('input:checked')?.focus({preventScroll: true})
                }}>
            <SortAscending size={17} aria-hidden="true"/>
            <span>{value === 'desc' ? '最新在前' : '最早在前'}</span>
            <CaretDown size={13} className="creation-history-sort-chevron" aria-hidden="true"/>
        </button>
        <div ref={panel} id={id} popover="auto" role="dialog" aria-label="排列顺序"
             className="creation-history-sort-popover" onToggle={event => setOpen(event.newState === 'open')}
             onKeyDown={event => {
                 if (event.key === 'Enter') {
                     event.preventDefault();
                     close()
                 }
             }}>
            <span className="creation-history-sort-label">按创建时间</span>
            <div role="radiogroup" aria-label="时间顺序">
                {([['desc', '最新在前'], ['asc', '最早在前']] as const).map(([order, label]) =>
                    <label className="creation-history-sort-option" key={order}>
                        <input type="radio" name={id} value={order} checked={value === order}
                               onChange={() => onChange(order)} onClick={event => {
                            if (event.detail > 0) close()
                        }}/>
                        <span>{label}</span><Check size={15} weight="bold" aria-hidden="true"/>
                    </label>)}
            </div>
        </div>
    </>
}

/** 服务端先筛选再分页；这里只展示当前查询结果并按图片原比例排列。 */
export default function CreationRecords({
                                            records,
                                            history,
                                            loading,
                                            historyError,
                                            selectedId,
                                            page,
                                            runningStatuses,
                                            filter,
                                            onFilterChange,
                                            onSelect,
                                            onDelete,
                                            onPageChange,
                                        }: {
    records: Generation[]
    history: Page<Generation> | null
    loading: boolean
    historyError: string
    selectedId?: string
    page: number
    runningStatuses: Generation['status'][]
    filter: GenerationHistoryFilter
    onFilterChange: (filter: GenerationHistoryFilter) => void
    onSelect: (task: Generation) => void
    onDelete: (task: Generation) => void
    onPageChange: (page: number) => void
}) {
    const statusName = {done: '已完成', running: '进行中', failed: '失败'}[filter.status]
    return <section className="creation-history" aria-labelledby="creation-history-title" aria-busy={loading}>
        <header>
            <div className="creation-history-total">
                <h2 tabIndex={-1} id="creation-history-title">{statusName}记录</h2>
                <span>{history ? history.total + ' 次创作' : loading ? <span className="skeleton-block skeleton-total"
                                                                             aria-label="正在读取数量">&nbsp;</span> : '—'}</span>
            </div>
            <span className="creation-history-order">按创建时间排序 · 图片完整展示</span>
        </header>
        <div className="creation-history-toolbar">
            <div className="creation-history-filters" role="group" aria-label="状态筛选">
                <span>状态</span>
                {([['done', '已完成'], ['running', '进行中'], ['failed', '失败']] as const).map(([value, label]) =>
                    <button type="button" key={value} aria-pressed={filter.status === value}
                            onClick={() => onFilterChange({...filter, status: value})}>{label}</button>)}
            </div>
            <div className="creation-history-tools">
                <label className="creation-history-search">
                    <MagnifyingGlass size={16} aria-hidden="true"/>
                    <input type="search" placeholder="搜索创作记录" aria-label="搜索创作记录" maxLength={200}
                           value={filter.keyword}
                           onChange={event => onFilterChange({...filter, keyword: event.target.value})}/>
                </label>
                <CreationSort value={filter.order} onChange={order => onFilterChange({...filter, order})}/>
            </div>
        </div>
        {loading && !records.length ?
            <div className="creation-history-loading" role="status" aria-label="正在加载创作记录">
                <span className="visually-hidden">正在加载创作记录…</span>
                <div className="masonry-grid creation-history-grid" aria-hidden="true">
                    {[0, 1, 2, 3].map(index => <div className="creation-history-skeleton" key={index}/>)}
                </div>
            </div> : !records.length ?
                <div className="creation-history-empty">
                    {historyError ? <WarningCircle size={32} weight="light" aria-hidden="true"/> :
                        <Sparkle size={32} weight="light" aria-hidden="true"/>}
                    <h3>{historyError ? '记录暂时未能加载' : filter.keyword.trim() ? '没有符合条件的记录' : '暂无' + statusName + '记录'}</h3>
                    <p>{historyError ? '请点击上方“刷新历史”重试。' : filter.keyword.trim() ? '换个关键词，或清除搜索后查看。' : '可以切换状态查看其他创作。'}</p>
                    {!historyError && (filter.keyword.trim()
                        ? <button type="button" className="button button-secondary"
                                  onClick={() => onFilterChange({...filter, keyword: ''})}>清除搜索</button>
                        : <Link className="button button-secondary" to="/">开始创作 <ArrowUpRight size={16}/></Link>)}
                </div> :
                <MasonryGrid className="creation-history-grid">{records.map((task, index) => {
                    const running = runningStatuses.includes(task.status)
                    const failed = ['FAILED', 'SAVE_FAILED', 'EXPIRED', 'ABANDONED'].includes(task.status)
                    return <article className="creation-history-card" key={task.id}>
                        <button type="button"
                                className={'creation-history-item' + (running ? ' is-running' : '') + (failed ? ' is-failed' : '') + (selectedId === task.id ? ' is-selected' : '')}
                                style={{animationDelay: Math.min(index, 5) * 35 + 'ms'}}
                                onClick={() => onSelect(task)} aria-haspopup="dialog">
                                <span className={'creation-history-thumb' + (task.image ? ' has-image' : '')}>
                                    {task.image ? <img src={task.image.preview} alt="" width={task.image.width}
                                                       height={task.image.height} loading="lazy"/> :
                                        <span className="creation-history-placeholder">
                                            {failed ? <WarningCircle size={30} weight="light" aria-hidden="true"/> :
                                                <Sparkle size={30} weight="light" aria-hidden="true"/>}
                                            <span>{running ? (task.status === 'SAVING' ? '正在保存图片' : '画面正在慢慢成形') : failed ? '点击查看失败原因' : task.status === 'SUCCEEDED' ? '图片已删除' : '暂无图片预览'}</span>
                                        </span>}
                                </span>
                            <span className="creation-history-copy">
                                    <span className="creation-history-prompt">{task.prompt}</span>
                                    <span className="creation-history-meta">
                                        <time dateTime={task.createTime.replace(' ', 'T')}
                                              title={task.createTime}>{Number(task.createTime.slice(5, 7))} 月 {Number(task.createTime.slice(8, 10))} 日 · {task.createTime.slice(11, 16)}</time>
                                    </span>
                                </span>
                        </button>
                        {!running && <button type="button" className="icon-button creation-history-delete"
                                             aria-label={'删除作品：' + task.prompt.slice(0, 40)} title="删除作品"
                                             aria-haspopup="dialog"
                                             onClick={() => onDelete(task)}><Trash size={16} aria-hidden="true"/>
                        </button>}
                    </article>
                })}</MasonryGrid>}
        {history && history.pages > 1 && <nav className="library-pagination" aria-label="创作记录分页">
            <button className="button button-secondary" disabled={loading || page <= 1}
                    onClick={() => onPageChange(page - 1)}>上一页
            </button>
            <span>{page} / {history.pages}</span>
            <button className="button button-secondary" disabled={loading || page >= history.pages}
                    onClick={() => onPageChange(page + 1)}>下一页
            </button>
        </nav>}
    </section>
}
