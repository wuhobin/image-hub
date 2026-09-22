import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {DownloadSimple} from '@phosphor-icons/react/dist/csr/DownloadSimple'
import {ImageSquare} from '@phosphor-icons/react/dist/csr/ImageSquare'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import type {Generation} from '../lib/types'
import {imageAspectRatio} from '../lib/rules'

/** 本次任务的作品展示区；有任务时才加载，复用原有预览、下载和编辑操作。 */
export default function CreationCurrent({
                                            task,
                                            running,
                                            statusLabel,
                                            qualityLabel,
                                            disabled,
                                            onPreview,
                                            onEdit,
                                            onDetail
                                        }: {
    task: Generation
    running: boolean
    statusLabel: string
    qualityLabel: string
    disabled: boolean
    onPreview: () => void
    onEdit: () => void
    onDetail: () => void
}) {
    const downloadUrl = task.image ? new URL(task.image.url) : null
    if (task.image && downloadUrl) downloadUrl.searchParams.set('attname', task.image.name)

    return <section className={'creation-current-task' + (running ? ' is-running' : '')}
                    aria-labelledby="creation-current-title" key={task.id}>
        <div className="creation-current-artwork">
            {task.image ?
                <button type="button" className="creation-current-image" aria-label="预览本次生成图片"
                        aria-haspopup="dialog" onClick={onPreview}>
                    <img src={task.image.url} alt={task.prompt}/>
                    <span className="creation-current-preview-hint"><ArrowUpRight size={14} aria-hidden="true"/>查看大图</span>
                </button> : <div className="creation-current-placeholder">
                    <div className="creation-detail-loader"><Sparkle size={34} weight="thin" aria-hidden="true"/></div>
                    <p>{running ? task.status === 'SAVING' ? '画面已生成，正在保存到你的图片库。' : '画面正在慢慢成形，你可以离开页面，任务会继续。'
                        : task.errorMessage || (task.status === 'SUCCEEDED' ? '图片已被删除，可重新使用这段描述创作。' : '本次任务已结束，预留积分已释放。')}</p>
                </div>}
        </div>
        <div className="creation-current-info">
            <header className="creation-current-heading">
                <h2 id="creation-current-title">本次创作</h2>
                <span className={'creation-current-status status-' + task.status.toLowerCase()} role="status">
                            <i aria-hidden="true"/>{statusLabel}
                        </span>
            </header>
            <p className="creation-current-prompt">{task.prompt}</p>
            <dl className="creation-current-meta">
                <div>
                    <dt>生成模型</dt>
                    <dd>{task.modelName}</dd>
                </div>
                <div>
                    <dt>画面尺寸</dt>
                    <dd>{task.size.replace('x', ' × ')}</dd>
                </div>
                <div>
                    <dt>比例 / 质量</dt>
                    <dd>{imageAspectRatio(task.size)} · {qualityLabel}</dd>
                </div>
                <div>
                    <dt>生成耗时</dt>
                    <dd>{task.durationSeconds != null ? task.durationSeconds + ' 秒' : running ? '进行中' : '—'}</dd>
                </div>
            </dl>
            <footer className="creation-current-actions">
                <p>{task.image ? '已保存到我的图片' : running ? '完成后会在这里展示图片' : '你可以修改描述后重新生成'}</p>
                {task.image && <div>
                    <a className="button button-primary" href={downloadUrl?.href} download={task.image.name}>
                        <DownloadSimple size={16} aria-hidden="true"/>下载图片
                    </a>
                    <button className="button button-secondary" disabled={disabled} onClick={onEdit}>
                        <ImageSquare size={16} aria-hidden="true"/>编辑图片
                    </button>
                </div>}
                <button className="quiet-link" onClick={onDetail} aria-haspopup="dialog">
                    查看创作详情<ArrowUpRight size={14} aria-hidden="true"/>
                </button>
            </footer>
        </div>
    </section>
}
