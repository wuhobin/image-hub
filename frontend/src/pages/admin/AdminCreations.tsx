import {useEffect, useState} from 'react'
import {Link, useSearchParams} from 'react-router-dom'
import {adminApi} from '../../lib/admin/api'
import type {Page, SharedCreation} from '../../lib/types'
import {Modal} from '../../components/Modal'
import {ArrowClockwise} from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {ImageSquare} from '@phosphor-icons/react/dist/csr/ImageSquare'
import {WarningCircle} from '@phosphor-icons/react/dist/csr/WarningCircle'

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

    return <div className="admin-creations-page">
        <div className="admin-page-title">
            <div><span className="admin-eyebrow">公开内容</span><h1>作品分享</h1>
                <p>查看公开作品，必要时停止展示。下架不删除作者的原作品。</p></div>
        </div>
        <section className="admin-directory" aria-label="作品分享列表">
            <div className="admin-directory-heading"><h2>分享作品 <span>{!loading && data ? data.total : '—'}</span>
            </h2>
                <button className="button button-small admin-refresh" disabled={loading}
                        aria-label="刷新作品列表" title="刷新作品列表"
                        onClick={() => setRevision(value => value + 1)}><ArrowClockwise size={17}
                                                                                        aria-hidden="true"/><span>刷新列表</span>
                </button>
            </div>
            <div className="admin-table-scroll" aria-busy={loading} tabIndex={0} aria-label="分享作品">
                <table className="admin-table">
                    <thead>
                    <tr>
                        <th scope="col">作品</th>
                        <th scope="col">作者</th>
                        <th scope="col">发布时间</th>
                        <th scope="col">状态</th>
                        <th scope="col">操作</th>
                    </tr>
                    </thead>
                    <tbody>{loading ? Array.from({length: 4}, (_, index) => <tr className="admin-skeleton" key={index}
                                                                                aria-hidden="true">
                            <td><span/></td>
                            <td><span/></td>
                            <td><span/></td>
                            <td><span/></td>
                            <td><span/></td>
                        </tr>)
                        : error ? <tr>
                                <td colSpan={5}>
                                    <div className="admin-table-state"><WarningCircle size={28} aria-hidden="true"/>
                                        <h3>作品暂时无法加载</h3><p role="alert">{error}</p>
                                        <button className="button button-secondary"
                                                onClick={() => setRevision(value => value + 1)}>重新加载
                                        </button>
                                    </div>
                                </td>
                            </tr>
                            : !data?.records.length ? <tr>
                                    <td colSpan={5}>
                                        <div className="admin-table-state"><ImageSquare size={28} aria-hidden="true"/>
                                            <h3>暂无分享作品</h3><p>用户公开分享作品后，会在这里显示。</p></div>
                                    </td>
                                </tr>
                                : data.records.map(work => <tr key={work.shareId}>
                                    <td>
                                        <div className="admin-creation-cell"><img className="admin-shared-thumbnail"
                                                                                  src={work.imageUrl}
                                                                                  alt={work.authorName + ' 的 AI 作品'}
                                                                                  loading="lazy"/>
                                            <div>
                                                <strong>{work.modelName}</strong><span>{work.width} × {work.height}</span>
                                            </div>
                                        </div>
                                    </td>
                                    <td>
                                        <div className="admin-user-cell"><span className="admin-user-avatar"
                                                                               aria-hidden="true">{work.authorName.slice(0, 1).toUpperCase()}</span><strong>{work.authorName}</strong>
                                        </div>
                                    </td>
                                    <td className="admin-date">{work.publishedTime}</td>
                                    <td><span
                                        className={'admin-status' + (work.shareStatus === 'PUBLIC' ? ' is-positive' : ' is-warning')}>{work.shareStatus === 'PUBLIC' ? '已公开' : '已下架'}</span>
                                    </td>
                                    <td>{work.shareStatus === 'PUBLIC' ? <div className="admin-row-actions">
                                        <Link className="quiet-link" target="_blank" rel="noopener noreferrer"
                                              to={'/share/' + work.shareId}>查看作品<ArrowUpRight size={13}
                                                                                                  aria-hidden="true"/></Link>
                                        <button className="quiet-link admin-danger-link" onClick={() => {
                                            setSelected(work);
                                            setActionError('')
                                        }}>下架
                                        </button>
                                    </div> : <span className="admin-muted">作者不可重新发布</span>}</td>
                                </tr>)}</tbody>
                </table>
            </div>
            <div className="admin-pagination"><span
                role="status">{loading ? '正在加载作品…' : data ? '共 ' + data.total + ' 件作品' : '暂无数据'}</span>
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
        <p className="admin-scroll-hint">左右滑动表格，查看完整作品信息与操作。</p>
        <p className="admin-directory-note">下架仅停止公开展示，作者的原作品和创作记录仍会保留。</p>
        {selected && <Modal title="下架这件作品？" onClose={() => {
            if (!busy) setSelected(null)
        }}>
            <div className="admin-confirm-preview admin-creation-cell"><img className="admin-shared-thumbnail"
                                                                            src={selected.imageUrl}
                                                                            alt={selected.authorName + ' 的 AI 作品'}/>
                <div>
                    <strong>{selected.authorName} 的作品</strong><span>{selected.modelName} · {selected.width} × {selected.height}</span>
                </div>
            </div>
            <p className="admin-modal-copy">下架后，作品会从广场移除，分享页失效，作者无法重新公开这件作品。原作品仍保留。</p>
            {actionError && <p className="admin-error" role="alert">{actionError}</p>}
            <div className="modal-actions">
                <button className="button button-secondary" disabled={busy} onClick={() => setSelected(null)}>取消
                </button>
                <button className="button button-danger" disabled={busy}
                        onClick={() => void block()}>{busy ? '正在下架…' : '确认下架'}</button>
            </div>
        </Modal>}
    </div>
}
