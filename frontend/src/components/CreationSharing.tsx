import {useEffect, useState} from 'react'
import {Link} from 'react-router-dom'
import {api} from '../lib/api'
import type {Generation} from '../lib/types'
import type {AppState} from '../App'
import '../pages/sharing.css'

/** 分享设置只读取作者接口；初次打开回读状态，避免历史列表中的旧状态覆盖管理员下架。 */
export default function CreationSharing({task, app, onChanged}: {
    task: Generation; app: AppState; onChanged: (task: Generation) => void
}) {
    const [current, setCurrent] = useState(task)
    const [promptPublic, setPromptPublic] = useState(task.promptPublic ?? true)
    const [loading, setLoading] = useState(true)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [revision, setRevision] = useState(0)
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void api<Generation>('/generations/' + task.id, {signal: controller.signal}).then(result => {
            if (controller.signal.aborted) return
            setCurrent(result)
            setPromptPublic(result.promptPublic ?? true)
        }).catch(error => {
            if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '分享设置读取失败')
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false)
        })
        return () => controller.abort()
    }, [task.id, revision])

    async function save(revoke = false) {
        if (busy || loading) return
        setBusy(true)
        setError('')
        try {
            let updated: Generation
            if (revoke) {
                await api('/generations/' + task.id + '/share', {method: 'DELETE'})
                updated = {...current, shareStatus: 'PRIVATE', shareId: null}
            } else {
                updated = await api<Generation>('/generations/' + task.id + '/share', {
                    method: 'PUT', body: JSON.stringify({promptPublic}),
                })
            }
            setCurrent(updated)
            onChanged(updated)
            app.notify(revoke ? '已撤销分享' : '分享设置已保存')
        } catch (error) {
            setError(error instanceof Error ? error.message : '分享设置保存失败')
        } finally {
            setBusy(false)
        }
    }

    const published = current.shareStatus === 'PUBLIC' && !!current.shareId
    const path = '/share/' + current.shareId
    const url = published ? window.location.origin + path : ''
    return <section className="creation-sharing" aria-labelledby="sharing-title" aria-busy={loading || busy}>
        <div className="sharing-heading"><h3 id="sharing-title">公开分享</h3><span>{
            loading ? '正在读取…' : current.shareStatus === 'BLOCKED' ? '已下架' : published ? '已公开' : '仅自己可见'
        }</span></div>
        {error && <p className="sharing-error" role="alert">{error}
            <button type="button" className="quiet-link"
                    disabled={busy} onClick={() => setRevision(value => value + 1)}>重新读取
            </button>
        </p>}
        {!loading && (current.shareStatus === 'BLOCKED'
            ? <p>作品已被管理员下架，不能重新公开；你仍可查看自己的作品。</p>
            : !current.image ? <p>原作品已删除，无法分享。</p> : <>
                <p>公开后，任何人都能在作品广场浏览，并看到你的用户名。</p>
                <label
                    className="sharing-toggle"><span><strong>公开提示词</strong><small>允许其他人查看描述，一键做同款</small></span>
                    <input type="checkbox" role="switch" checked={promptPublic} disabled={busy}
                           onChange={event => setPromptPublic(event.target.checked)}/></label>
                <div className="sharing-actions">
                    <button type="button" className="button button-primary button-small" disabled={busy}
                            onClick={() => void save()}>{busy ? '正在保存…' : published ? '保存分享设置' : '公开到作品广场'}</button>
                    {published && <button type="button" className="button button-secondary button-small" disabled={busy}
                                          onClick={() => void save(true)}>撤销分享</button>}
                </div>
                {published && <>
                    <label className="sharing-link-label" htmlFor="creation-share-url">分享链接</label>
                    <input id="creation-share-url" className="sharing-link" value={url} readOnly
                           onFocus={event => event.currentTarget.select()}/>
                    <div className="sharing-actions">
                        <button type="button" className="quiet-link" onClick={() => {
                            void navigator.clipboard.writeText(url).then(() => app.notify('分享链接已复制'))
                                .catch(() => app.notify('复制失败，请选中上方链接手动复制', true))
                        }}>复制分享链接
                        </button>
                        <Link className="quiet-link" to={path}>查看分享页 →</Link></div>
                    <p className="sharing-note">撤销后广场和分享页不再展示，已获得的原始图片链接仍可能访问。</p>
                </>}
            </>)}
    </section>
}
