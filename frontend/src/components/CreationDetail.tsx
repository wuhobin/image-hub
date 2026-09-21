import {Copy} from '@phosphor-icons/react/dist/csr/Copy'
import {DownloadSimple} from '@phosphor-icons/react/dist/csr/DownloadSimple'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import {ArrowUUpLeft} from '@phosphor-icons/react/dist/csr/ArrowUUpLeft'
import {ImageSquare} from '@phosphor-icons/react/dist/csr/ImageSquare'
import type {AppState} from '../App'
import type {Generation} from '../lib/types'
import {generationResolution, imageAspectRatio} from '../lib/rules'
import {Modal} from './Modal'

/** 用户打开详情时再加载，保持原有弹窗内容、预览和复用操作。 */
export default function CreationDetail({
                                           task,
                                           app,
                                           running,
                                           statusLabel,
                                           qualityLabel,
                                           disabled,
                                           onReuse,
                                           onEdit,
                                           onClose
                                       }: {
    task: Generation
    app: AppState
    running: boolean
    statusLabel: string
    qualityLabel: string
    disabled: boolean
    onReuse: (task: Generation) => void
    onEdit: (task: Generation) => void
    onClose: () => void
}) {
    async function copyPrompt(text: string) {
        try {
            await navigator.clipboard.writeText(text);
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
                        <span>{imageAspectRatio(task.size)}</span><span>{task.size.replace('x', '×')}</span></div>
                    {task.image &&
                        <a className="creation-detail-download" href={downloadUrl?.href} download={task.image.name}
                           aria-label="下载图片" title="下载图片"><DownloadSimple size={18}/></a>}
                </div>
                {task.image ?
                    <button type="button" className="creation-image" aria-label="预览生成图片" aria-haspopup="dialog"
                            title="点击预览" onClick={() => app.setPreview(task.image)}><img src={task.image.url}
                                                                                             alt={task.prompt}/>
                    </button> :
                    <div className="creation-placeholder" aria-live="polite">
                        <div className="creation-detail-loader"><Sparkle size={38} weight="thin" aria-hidden="true"/>
                        </div>
                        <h3>{statusLabel}</h3>
                        <p>{task.status === 'GENERATING' || task.status === 'QUEUED' ? '你可以离开页面，任务会在后台继续。' : task.status === 'SAVING' ? '图片已生成，正在保存到你的图库。' : task.status === 'SUCCEEDED' ? '生成已完成，图片已被删除。' : task.errorMessage || '本次任务已结束，预留积分已释放。'}</p>
                    </div>}
            </div>
            <div className="creation-detail-panel">
                <div className="creation-detail-content">
                    <section className="creation-detail-prompt" aria-labelledby="creation-detail-prompt-title">
                        <div className="creation-detail-section-heading"><h3
                            id="creation-detail-prompt-title">输入内容</h3>
                            <button className="icon-button" aria-label="复制提示词" title="复制提示词"
                                    onClick={() => void copyPrompt(task.prompt)}><Copy size={15}/></button>
                        </div>
                        <p tabIndex={0} aria-label="完整提示词">{task.prompt}</p>
                    </section>
                    <section className="creation-detail-settings" aria-labelledby="creation-detail-settings-title">
                        <h3 id="creation-detail-settings-title">参数配置</h3>
                        <dl className="creation-detail-parameters">
                            <div className="creation-detail-model">
                                <dt>生成模型</dt>
                                <dd>{task.modelName}</dd>
                            </div>
                            <div>
                                <dt>尺寸</dt>
                                <dd>{task.size.replace('x', '×')}</dd>
                            </div>
                            <div>
                                <dt>质量</dt>
                                <dd>{qualityLabel}</dd>
                            </div>
                            <div>
                                <dt>画面比例</dt>
                                <dd>{imageAspectRatio(task.size)}</dd>
                            </div>
                            <div>
                                <dt>分辨率</dt>
                                <dd>{generationResolution(task.size) || '自定义'}</dd>
                            </div>
                            {task.image?.type && <div>
                                <dt>图片格式</dt>
                                <dd>{task.image.type.toUpperCase()}</dd>
                            </div>}
                            <div>
                                <dt>生成数量</dt>
                                <dd>1 张</dd>
                            </div>
                            <div className="creation-detail-duration"
                                 title="实际生成及保存耗时，不包含排队和失败后的清理时间">
                                <dt>生成耗时</dt>
                                <dd>{task.durationSeconds != null ? task.durationSeconds + ' 秒' : running ? '进行中' : '暂无数据'}</dd>
                            </div>
                        </dl>
                    </section>
                    <div className="creation-detail-meta"><span
                        className={'creation-detail-status status-' + task.status.toLowerCase()} role="status"><i
                        aria-hidden="true"/>{statusLabel}</span>
                        <time dateTime={task.createTime.replace(' ', 'T')}>创建于 {task.createTime}</time>
                    </div>
                    {task.image && <p className="creation-detail-saved">已保存到我的图片</p>}
                </div>
                <footer className="creation-detail-actions">
                    <button className="button creation-detail-reuse" disabled={disabled} onClick={() => onReuse(task)}>
                        <ArrowUUpLeft size={17}/>复用描述
                    </button>
                    {task.image && <button className="button button-secondary creation-detail-edit" disabled={disabled}
                                           onClick={() => onEdit(task)}><ImageSquare size={17}/>编辑图片</button>}
                    {task.image &&
                        <button className="button button-secondary" onClick={() => void app.copyUrl(task.image!.url)}>
                            <Copy size={16}/>复制链接</button>}
                </footer>
            </div>
        </section>
    </Modal>

}
