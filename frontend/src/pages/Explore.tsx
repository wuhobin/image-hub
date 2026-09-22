import {useEffect, useState} from 'react'
import {Link, useSearchParams} from 'react-router-dom'
import {api} from '../lib/api'
import type {Page, SharedCreation} from '../lib/types'
import './sharing.css'

/** 公开广场独立于登录态；切回页面重新查询，避免继续展示已撤销作品。 */
export default function Explore() {
    const [params, setParams] = useSearchParams()
    const requested = Number(params.get('page') || 1)
    const page = Number.isSafeInteger(requested) && requested > 0 ? requested : 1
    const [data, setData] = useState<Page<SharedCreation> | null>(null)
    const [error, setError] = useState('')
    const [loading, setLoading] = useState(true)
    const [revision, setRevision] = useState(0)
    useEffect(() => {
        const refresh = () => setRevision(value => value + 1)
        window.addEventListener('focus', refresh)
        return () => window.removeEventListener('focus', refresh)
    }, [])
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setData(null)
        setError('')
        void api<Page<SharedCreation>>('/public/creations?page=' + page + '&pageSize=12',
            {signal: controller.signal, cache: 'no-store'}).then(result => {
            if (!controller.signal.aborted) setData(result)
        }).catch(error => {
            if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '作品读取失败')
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false)
        })
        return () => controller.abort()
    }, [page, revision])

    return <main id="main" className="explore-page">
        <header className="explore-heading">
            <div><span className="section-kicker">由创作者公开分享</span>
                <h1>作品广场</h1><p>看看别人的想象，从一张作品开始新的创作。</p></div>
            <Link className="button button-secondary" to="/">开始创作 →</Link></header>
        <div className="explore-toolbar">
            <span>最新发布</span><span>{data ? data.total + ' 件作品' : '公开 AI 创作'}</span></div>
        {loading ? <div className="sharing-state" role="status">正在加载作品…</div>
            : error ? <div className="sharing-state" role="alert"><p>{error}</p>
                    <button className="button button-secondary"
                            onClick={() => setRevision(value => value + 1)}>重新加载
                    </button>
                </div>
                : !data?.records.length ?
                    <div className="sharing-state"><h2>{page > 1 ? '这一页暂无作品' : '等待第一份灵感'}</h2>
                        <p>{page > 1 ? '部分作品可能已停止分享，返回第一页看看。' : '完成创作后，在作品详情中选择公开分享，让更多人看见。'}</p>
                        {page > 1 ? <button className="button button-secondary"
                                            onClick={() => setParams({page: '1'})}>返回第一页</button>
                            : <Link className="button button-primary" to="/">开始创作</Link>}</div>
                    : <div className="explore-grid">{data.records.map(work => <Link className="explore-card"
                                                                                    key={work.shareId}
                                                                                    to={'/share/' + work.shareId}
                                                                                    aria-label={'查看 ' + work.authorName + ' 的 AI 作品'}>
                        <div className="explore-image"><img src={work.imageUrl}
                                                            alt={work.authorName + ' 分享的 AI 作品'}
                                                            loading="lazy" width={work.width}
                                                            height={work.height}/><span>AI 生成</span></div>
                        <div className="explore-caption"><span
                            className="explore-author">{work.authorName}</span><span>{work.modelName}</span></div>
                        <p className="explore-prompt">{work.promptPublic ? work.prompt : '作者未公开提示词'}</p>
                    </Link>)}</div>}
        {data && data.pages > 1 && <nav className="sharing-pagination" aria-label="作品分页">
            <button className="button button-secondary button-small" disabled={loading || page <= 1}
                    onClick={() => setParams({page: String(page - 1)})}>上一页
            </button>
            <span>{page} / {data.pages}</span>
            <button className="button button-secondary button-small" disabled={loading || page >= data.pages}
                    onClick={() => setParams({page: String(page + 1)})}>下一页
            </button>
        </nav>}
    </main>
}
