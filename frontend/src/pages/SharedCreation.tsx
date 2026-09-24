import {useEffect, useState} from 'react'
import {Link, useNavigate, useParams} from 'react-router-dom'
import type {AppState} from '../App'
import {api} from '../lib/api'
import type {SharedCreation as Work} from '../lib/types'
import './sharing.css'

/** 分享详情只读公开接口；做同款前再次确认可见性，不携带作者原参考图或触发生图。 */
export default function SharedCreation({app}: { app: AppState }) {
    const {shareId} = useParams()
    const navigate = useNavigate()
    const [work, setWork] = useState<Work | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)
    const [revision, setRevision] = useState(0)
    const path = '/public/creations/' + encodeURIComponent(shareId || '')
    useEffect(() => {
        const refresh = () => setRevision(value => value + 1)
        window.addEventListener('focus', refresh)
        return () => window.removeEventListener('focus', refresh)
    }, [])
    useEffect(() => {
        const controller = new AbortController()
        setError('')
        setLoading(true)
        void api<Work>(path, {signal: controller.signal, cache: 'no-store'}).then(result => {
            if (!controller.signal.aborted) setWork(result)
        }).catch(error => {
            if (!controller.signal.aborted) {
                setWork(null);
                setError(error instanceof Error ? error.message : '作品读取失败')
            }
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false)
        })
        return () => controller.abort()
    }, [path, revision])

    async function remix() {
        if (busy || loading || app.initializing) return
        setBusy(true)
        try {
            const current = await api<Work>(path, {cache: 'no-store'})
            setWork(current)
            if (!current.promptPublic || !current.prompt) {
                app.notify('作者已关闭提示词，暂时无法做同款', true)
                return
            }
            const state = {
                from: '/', creationPrompt: current.prompt,
                creationPreset: {modelId: current.modelId, size: current.size, quality: current.quality}
            }
            navigate(app.user ? '/' : '/login', {state})
        } catch (error) {
            setWork(null)
            setError(error instanceof Error ? error.message : '作品读取失败')
        } finally {
            setBusy(false)
        }
    }

    return <main id="main" className="shared-page" aria-busy={loading}>
        <Link className="quiet-link shared-back" to="/explore">← 返回作品广场</Link>
        {loading && !work ? <div className="shared-work shared-skeleton" role="status" aria-label="正在加载作品">
                <div className="shared-artwork skeleton-block" aria-hidden="true"/>
                <div className="shared-information" aria-hidden="true">
                    <span className="skeleton-block skeleton-title"/>
                    <div className="shared-settings"><span className="skeleton-block skeleton-detail"/></div>
                    <div className="skeleton-block shared-prompt-placeholder"/>
                </div>
            </div>
            : !work ? <div className="sharing-state"><h1>暂时无法查看这件作品</h1><p role="alert">{error}</p>
                    <button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>重新加载
                    </button>
                </div>
                : <article className="shared-work">
                    <div className="shared-artwork"><img src={work.imageUrl} alt={work.authorName + ' 分享的 AI 作品'}
                                                         width={work.width} height={work.height}/><span
                        className="shared-ai-label">AI 生成作品</span></div>
                    <div className="shared-information"><span className="section-kicker">公开作品</span>
                        <h1>{work.authorName} 的创作</h1>
                        <p className="shared-time">发布于 <time
                            dateTime={work.publishedTime.replace(' ', 'T')}>{work.publishedTime}</time></p>
                        <dl className="shared-settings">
                            <div>
                                <dt>模型</dt>
                                <dd>{work.modelName}</dd>
                            </div>
                            <div>
                                <dt>尺寸</dt>
                                <dd>{work.size.replace('x', ' × ')}</dd>
                            </div>
                            <div>
                                <dt>质量</dt>
                                <dd>{{
                                    low: '标准',
                                    medium: '精细',
                                    high: '高质量',
                                    auto: '自动'
                                }[work.quality] || work.quality}</dd>
                            </div>
                        </dl>
                        <section className="shared-prompt"><h2>提示词</h2>
                            <p>{work.promptPublic ? work.prompt : '作者未公开提示词。你仍可以欣赏作品，或开始自己的创作。'}</p>
                        </section>
                        {work.promptPublic && work.prompt ? <>
                            <button className="button button-primary shared-remix"
                                    disabled={busy || loading || app.initializing}
                                    onClick={() => void remix()}>{busy ? '正在准备…' : '一键做同款'}</button>
                            <p className="sharing-note">带入公开提示词和可用参数，确认提交后才生成并扣积分。参考图不随作品分享。</p>
                        </> : <Link className="button button-secondary" to="/">开始自己的创作</Link>}
                        <button className="button button-secondary shared-copy" onClick={() => {
                            void navigator.clipboard.writeText(window.location.origin + '/share/' + work.shareId)
                                .then(() => app.notify('分享链接已复制')).catch(() => app.notify('复制失败，请复制浏览器地址栏链接', true))
                        }}>复制分享链接
                        </button>
                    </div>
                </article>}
    </main>
}
