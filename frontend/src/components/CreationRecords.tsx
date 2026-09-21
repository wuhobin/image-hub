import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {WarningCircle} from '@phosphor-icons/react/dist/csr/WarningCircle'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import type {Generation, Page} from '../lib/types'
import {generationResolution, imageAspectRatio} from '../lib/rules'

/** 记录列表仅在记录页加载，首页无需解析历史卡片及其图标。 */
export default function CreationRecords({
                                            records,
                                            history,
                                            loading,
                                            historyError,
                                            selectedId,
                                            page,
                                            labels,
                                            runningStatuses,
                                            onSelect,
                                            onPageChange
                                        }: {
    records: Generation[]
    history: Page<Generation> | null
    loading: boolean
    historyError: string
    selectedId?: string
    page: number
    labels: Record<Generation['status'], string>
    runningStatuses: Generation['status'][]
    onSelect: (task: Generation) => void
    onPageChange: (page: number) => void
}) {
    return <section className="creation-history" aria-labelledby="creation-history-title"
                    aria-busy={loading && !records.length}>
        <header>
            <div><h2 id="creation-history-title">全部记录</h2></div>
            <span>{loading && !records.length ? '正在读取…' : Math.max(history?.total || 0, records.length) + ' 次创作'}</span>
        </header>
        {loading && !records.length ?
            <div className="creation-history-loading" role="status" aria-label="正在加载创作记录">
                <span className="visually-hidden">正在加载创作记录…</span>
                <div className="creation-history-grid" aria-hidden="true">
                    {[0, 1, 2].map(index => <div className="creation-history-skeleton" key={index}/>)}
                </div>
            </div> : !records.length ?
                <p className="creation-history-empty">{historyError ? '暂时无法读取创作记录，请稍后重试。' : '还没有创作记录，试着生成第一张图片。'}</p> :
                <div className="creation-history-grid">{records.map((task, index) => {
                    const running = runningStatuses.includes(task.status)
                    const failed = task.status === 'FAILED' || task.status === 'SAVE_FAILED' || task.status === 'EXPIRED'
                    return <button
                        className={'creation-history-item' + (running ? ' is-running' : '') + (failed ? ' is-failed' : '') + (selectedId === task.id ? ' is-selected' : '')}
                        key={task.id} style={{animationDelay: Math.min(index, 5) * 35 + 'ms'}}
                        onClick={() => onSelect(task)} aria-haspopup="dialog">
            <span className="creation-history-thumb">
              {task.image ? <img src={task.image.preview} alt="" loading="lazy"/> :
                  <span className="creation-history-placeholder">
                {failed ? <WarningCircle size={30} weight="light" aria-hidden="true"/> :
                    <Sparkle size={30} weight="light" aria-hidden="true"/>}
                      <span>{running ? (task.status === 'SAVING' ? '正在保存图片' : '画面正在慢慢成形') : failed ? '点击查看失败原因' : task.status === 'SUCCEEDED' ? '图片已删除' : '暂无图片预览'}</span>
              </span>}
                <span className={'creation-status status-' + task.status.toLowerCase()} aria-live="polite"><i
                    className="creation-status-indicator" aria-hidden="true"/>{labels[task.status]}{running &&
                    <span className="creation-status-dots" aria-hidden="true"><i/><i/><i/></span>}</span>
              <span className="creation-history-ratio">{imageAspectRatio(task.size)}</span>
            </span>
                        <span className="creation-history-copy">
              <span className="creation-history-prompt">{task.prompt}</span>
              <span
                  className="creation-history-model"><span>{task.modelName}</span><span>{generationResolution(task.size) || task.size.replace('x', '×')}</span></span>
              <span className="creation-history-footer"><time
                  dateTime={task.createTime.replace(' ', 'T')}>{task.createTime.slice(0, 16)}</time><ArrowUpRight
                  size={16} aria-hidden="true"/></span>
            </span>
                    </button>
                })}</div>}
        {history && history.pages > 1 && <div className="library-pagination">
            <button className="button button-secondary" disabled={page <= 1}
                    onClick={() => onPageChange(page - 1)}>上一页
            </button>
            <span>{page} / {history.pages}</span>
            <button className="button button-secondary" disabled={page >= history.pages}
                    onClick={() => onPageChange(page + 1)}>下一页
            </button>
        </div>}
    </section>
}
