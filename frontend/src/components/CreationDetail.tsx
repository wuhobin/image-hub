import {useState} from 'react'
import {Copy} from '@phosphor-icons/react/dist/csr/Copy'
import {DownloadSimple} from '@phosphor-icons/react/dist/csr/DownloadSimple'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import {ArrowUUpLeft} from '@phosphor-icons/react/dist/csr/ArrowUUpLeft'
import {ImageSquare} from '@phosphor-icons/react/dist/csr/ImageSquare'
import {LinkSimple} from '@phosphor-icons/react/dist/csr/LinkSimple'
import {ArrowsOut} from '@phosphor-icons/react/dist/csr/ArrowsOut'
import type {AppState} from '../App'
import type {Generation} from '../lib/types'
import {generationResolution, imageAspectRatio} from '../lib/rules'
import {Modal} from './Modal'
import CreationSharing from './CreationSharing'

/** 以作品预览为主，创作信息和分享设置分区查看，保留独立预览与复用操作。 */
export default function CreationDetail({
                                           task,
                                           app,
                                           running,
                                           statusLabel,
                                           qualityLabel,
                                           disabled,
                                           onReuse,
                                           onEdit,
                                           onChanged,
                                           onClose,
                                       }: {
    task: Generation
    app: AppState
    running: boolean
    statusLabel: string
    qualityLabel: string
    disabled: boolean
    onReuse: (task: Generation) => void
    onEdit: (task: Generation) => void
    onChanged: (task: Generation) => void
    onClose: () => void
}) {
    const [view, setView] = useState<'details' | 'sharing'>('details')
    const canShare = task.status === 'SUCCEEDED' && !!task.image
    const showSharing = canShare && view === 'sharing'

    async function copyPrompt() {
        try {
            await navigator.clipboard.writeText(task.prompt)
            app.notify('提示词已复制')
        } catch {
            app.notify('复制失败，请选中提示词后手动复制', true)
        }
    }

    const downloadUrl = task.image ? new URL(task.image.url) : null
    // 七牛使用 attname 返回附件下载响应，跨域图片无需先 fetch 到浏览器内存。
    if (task.image && downloadUrl) downloadUrl.searchParams.set('attname', task.image.name)

    return <Modal title="创作记录详情" className="creation-detail-modal" onClose={onClose}>
        <section className="creation-result" aria-label="生成结果">
            <div className={'creation-detail-preview' + (running ? ' is-running' : '')}>
                <div className="creation-detail-image-tools">
                    <div className="creation-detail-badges">
                        <span>{imageAspectRatio(task.size)}</span><span>{task.size.replace('x', ' × ')}</span>
                    </div>
                    {task.image && <div className="creation-detail-image-actions">
                        <button className="icon-button" aria-label="复制图片链接" title="复制图片链接"
                                onClick={() => void app.copyUrl(task.image!.url)}><LinkSimple size={18}/></button>
                        <a className="icon-button" href={downloadUrl?.href} download={task.image.name}
                           aria-label="下载图片" title="下载图片"><DownloadSimple size={18}/></a>
                    </div>}
                </div>
                {task.image
                    ? <button type="button" className="creation-image" aria-label="预览生成图片" aria-haspopup="dialog"
                              onClick={() => app.setPreview(task.image)}>
                        <img src={task.image.url} alt={task.prompt}/>
                        <span className="creation-detail-preview-hint"><ArrowsOut size={14}/>查看大图</span>
                    </button>
                    : <div className="creation-placeholder" aria-live="polite">
                        <div className="creation-detail-loader"><Sparkle size={38} weight="thin" aria-hidden="true"/>
                        </div>
                        <h3>{statusLabel}</h3>
                        <p>{task.status === 'GENERATING' || task.status === 'QUEUED' ? '你可以离开页面，任务会在后台继续。' : task.status === 'SAVING' ? '图片已生成，正在保存到你的图库。' : task.status === 'SUCCEEDED' ? '生成已完成，图片已被删除。' : task.errorMessage || '本次任务已结束，预留积分已释放。'}</p>
                    </div>}
            </div>
            <div className="creation-detail-panel">
                <header className="creation-detail-summary">
                    <div className="creation-detail-meta">
            <span className={'creation-detail-status status-' + task.status.toLowerCase()} role="status">
              <i aria-hidden="true"/>{statusLabel}
            </span>
                        {task.image && <span>已保存到我的图片</span>}
                    </div>
                    <time dateTime={task.createTime.replace(' ', 'T')}>{task.createTime}</time>
                </header>
                {canShare && <div className="creation-detail-tabs" role="group" aria-label="作品信息">
                    <button type="button" aria-pressed={!showSharing} aria-controls="creation-details"
                            onClick={() => setView('details')}>创作详情
                    </button>
                    <button type="button" aria-pressed={showSharing} aria-controls="creation-sharing-panel"
                            onClick={() => setView('sharing')}>分享设置
                    </button>
                </div>}
                <div className="creation-detail-content">
                    <div id="creation-details" hidden={showSharing}>
                        <section className="creation-detail-prompt" aria-label="提示词">
                            <button className="icon-button" aria-label="复制提示词" title="复制提示词"
                                    onClick={() => void copyPrompt()}><Copy size={16}/></button>
                            <p tabIndex={0} aria-label="完整提示词">{task.prompt}</p>
                        </section>
                        <section className="creation-detail-settings" aria-label="生成参数">
                            <dl className="creation-detail-parameters">
                                <div className="creation-detail-model">
                                    <dt>生成模型</dt>
                                    <dd>{task.modelName}</dd>
                                </div>
                                <div>
                                    <dt>图片尺寸</dt>
                                    <dd>{task.size.replace('x', ' × ')}</dd>
                                </div>
                                <div>
                                    <dt>画面质量</dt>
                                    <dd>{qualityLabel}</dd>
                                </div>
                                <div>
                                    <dt>画面比例</dt>
                                    <dd>{imageAspectRatio(task.size)} · {generationResolution(task.size) || '自定义'}</dd>
                                </div>
                                <div>
                                    <dt>输出格式</dt>
                                    <dd>{task.image?.type?.toUpperCase() || '—'} · 1 张</dd>
                                </div>
                                <div className="creation-detail-duration"
                                     title="实际生成及保存耗时，不包含排队和失败后的清理时间">
                                    <dt>生成耗时</dt>
                                    <dd>{task.durationSeconds != null ? task.durationSeconds + ' 秒' : running ? '进行中' : '暂无数据'}</dd>
                                </div>
                            </dl>
                        </section>
                    </div>
                    {canShare && <div id="creation-sharing-panel" hidden={!showSharing}>
                        <CreationSharing key={task.id} task={task} app={app} onChanged={onChanged}/>
                    </div>}
                </div>
                <footer className="creation-detail-actions">
                    <button className="button creation-detail-reuse" disabled={disabled} onClick={() => onReuse(task)}>
                        <ArrowUUpLeft size={17}/>复用描述
                    </button>
                    {task.image && <button className="button button-secondary creation-detail-edit" disabled={disabled}
                                           onClick={() => onEdit(task)}><ImageSquare size={17}/>编辑图片</button>}
                </footer>
            </div>
        </section>
    </Modal>
}
