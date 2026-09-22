import {useEffect, useState} from 'react'
import {Link, useSearchParams} from 'react-router-dom'
import {adminApi} from '../../lib/admin/api'
import type {Page, SharedCreation} from '../../lib/types'
import {Modal} from '../../components/Modal'
import '../sharing.css'

/** 管理分享状态，不删除原文件；下架需要明确选择作品并确认。 */
export default function AdminCreations() {
    const [params, setParams] = useSearchParams()
    const requested = Number(params.get('page') || 1)
    const page = Number.isSafeInteger(requested) && requested > 0 ? requested : 1
    const [data, setData] = useState<Page<SharedCreation> | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [revision, setRevision] = useState(0)
    const [selected, setSelected] = useState<SharedCreation | null>(null)
    const [busy, setBusy] = useState(false)
    const [actionError, setActionError] = useState('')
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void adminApi<Page<SharedCreation>>('/creations?page=' + page + '&pageSize=20', {signal: controller.signal})
            .then(result => {
                if (!controller.signal.aborted) setData(result)
            })
            .catch(error => {
                if (!controller.signal.aborted) {
                    setData(null);
                    setError(error instanceof Error ? error.message : '读取失败')
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [page, revision])

    async function block() {
        if (!selected || busy) return
        setBusy(true)
        setActionError('')
        try {
            await adminApi('/creations/' + selected.shareId + '/block', {method: 'POST'})
            setSelected(null)
            setRevision(value => value + 1)
        } catch (error) {
            setActionError(error instanceof Error ? error.message : '下架失败')
        } finally {
            setBusy(false)
        }
    }

    return <div>
        <div className="admin-page-title">
            <div><span className="admin-eyebrow">公开内容</span><h1>作品分享</h1>
                <p>查看公开作品，必要时停止展示。下架不删除作者的原作品。</p></div>
        </div>
        <section className="admin-directory" aria-label="作品分享列表">
            <div className="admin-directory-heading"><h2>分享作品 <span>{data?.total ?? '—'}</span></h2>
                <button className="button button-secondary button-small" disabled={loading}
                        onClick={() => setRevision(value => value + 1)}>刷新列表
                </button>
            </div>
            <div className="admin-table-scroll" aria-busy={loading} tabIndex={0} aria-label="分享作品">
                <table className="admin-table">
                    <thead>
                    <tr>
                        <th>作品</th>
                        <th>作者</th>
                        <th>发布时间</th>
                        <th>状态</th>
                        <th>操作</th>
                    </tr>
                    </thead>
                    <tbody>{loading ? <tr>
                            <td colSpan={5}>
                                <div className="admin-table-state" role="status">正在加载作品…</div>
                            </td>
                        </tr>
                        : error ? <tr>
                                <td colSpan={5}>
                                    <div className="admin-table-state" role="alert">{error}</div>
                                </td>
                            </tr>
                            : !data?.records.length ? <tr>
                                    <td colSpan={5}>
                                        <div className="admin-table-state">暂无分享作品</div>
                                    </td>
                                </tr>
                                : data.records.map(work => <tr key={work.shareId}>
                                    <td><img className="admin-shared-thumbnail" src={work.imageUrl}
                                             alt={work.authorName + ' 的 AI 作品'} loading="lazy"/></td>
                                    <td>{work.authorName}</td>
                                    <td className="admin-date">{work.publishedTime}</td>
                                    <td>{work.shareStatus === 'PUBLIC' ? '已公开' : '已下架'}</td>
                                    <td>{work.shareStatus === 'PUBLIC' ? <div className="sharing-actions">
                                        <Link className="quiet-link" target="_blank" rel="noopener noreferrer"
                                              to={'/share/' + work.shareId}>查看作品</Link>
                                        <button className="button button-secondary button-small" onClick={() => {
                                            setSelected(work);
                                            setActionError('')
                                        }}>下架
                                        </button>
                                    </div> : '作者不可重新发布'}</td>
                                </tr>)}</tbody>
                </table>
            </div>
            <div className="admin-pagination"><span>{data ? '共 ' + data.total + ' 件作品' : '暂无数据'}</span>
                <div>
                    <button className="button button-secondary button-small" disabled={loading || page <= 1}
                            onClick={() => setParams({page: String(page - 1)})}>上一页
                    </button>
                    <span>{page} / {Math.max(1, data?.pages || 1)}</span>
                    <button className="button button-secondary button-small"
                            disabled={loading || !data || page >= data.pages}
                            onClick={() => setParams({page: String(page + 1)})}>下一页
                    </button>
                </div>
            </div>
        </section>
        {selected && <Modal title="下架这件作品？" onClose={() => {
            if (!busy) setSelected(null)
        }}>
            <p>下架后，作品会从广场移除，分享页失效，作者无法重新公开这件作品。原作品仍保留。</p>
            {actionError && <p className="admin-error" role="alert">{actionError}</p>}
            <div className="sharing-actions" style={{marginTop: 24}}>
                <button className="button button-primary" disabled={busy}
                        onClick={() => void block()}>{busy ? '正在下架…' : '确认下架'}</button>
                <button className="button button-secondary" disabled={busy} onClick={() => setSelected(null)}>取消
                </button>
            </div>
        </Modal>}
    </div>
}
