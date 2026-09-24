import {useEffect, useState} from 'react'
import {Copy} from '@phosphor-icons/react/dist/csr/Copy'
import {Users} from '@phosphor-icons/react/dist/csr/Users'
import {api} from '../lib/api'
import {invitationStatusLabels} from '../lib/types'
import type {InvitationInfo, InvitationRecord, Page} from '../lib/types'
import './invitation.css'

/** 展示本人邀请码与奖励状态，不读取其他用户邮箱或注册来源。 */
export function InvitationCard() {
    const [info, setInfo] = useState<InvitationInfo | null>(null)
    const [records, setRecords] = useState<Page<InvitationRecord> | null>(null)
    const [page, setPage] = useState(1)
    const [revision, setRevision] = useState(0)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void Promise.all([
            api<InvitationInfo>('/invitations', {signal: controller.signal}),
            api<Page<InvitationRecord>>('/invitations/records?page=' + page + '&pageSize=5', {signal: controller.signal}),
        ]).then(([nextInfo, nextRecords]) => {
            if (!controller.signal.aborted) {
                setInfo(nextInfo);
                setRecords(nextRecords)
            }
        }).catch(error => {
            if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '邀请信息读取失败')
        }).finally(() => {
            if (!controller.signal.aborted) setLoading(false)
        })
        const refresh = () => setRevision(value => value + 1)
        window.addEventListener('focus', refresh)
        return () => {
            controller.abort();
            window.removeEventListener('focus', refresh)
        }
    }, [page, revision])

    const link = info?.inviteCode ? window.location.origin + '/register?invite=' + encodeURIComponent(info.inviteCode) : ''

    async function copy(value: string) {
        try {
            await navigator.clipboard.writeText(value)
            setNotice('已复制，可以分享给朋友了')
        } catch {
            setNotice('自动复制不可用，请选中下方内容手动复制')
        }
    }

    return <section className="profile-invitation" aria-labelledby="invitation-title" aria-busy={loading}>
        <header className="invitation-heading">
            <div><h2 id="invitation-title"><Users size={18} aria-hidden="true"/>邀请奖励</h2>
                <p>{info ? info.rewards.enabled
                        ? '好友完成邮箱验证并注册，你获得 ' + info.rewards.inviterPoints + ' 积分，好友额外获得 ' + info.rewards.inviteePoints + ' 积分。'
                        : '邀请奖励活动已暂停，好友仍可注册；已有待审核奖励继续处理。'
                    : '分享邀请码，和朋友一起开始创作。'}</p></div>
            <button type="button" className="text-button" disabled={loading}
                    onClick={() => setRevision(value => value + 1)}>
                {loading ? '读取中…' : '刷新状态'}
            </button>
        </header>
        {error && <p className="field-error" role="alert">{error}</p>}
        {loading && !info && <div className="invitation-loading" role="status" aria-label="正在读取邀请信息">
            <span className="skeleton-block"/><span className="skeleton-block"/>
        </div>}
        {info && <>
            <div className="invitation-share">
                <div><label htmlFor="my-invite-code">我的邀请码</label>
                    <div className="invitation-copy">
                        <input id="my-invite-code" readOnly value={info.inviteCode || ''}
                               onFocus={event => event.target.select()}/>
                        <button type="button" className="button button-secondary"
                                onClick={() => void copy(info.inviteCode || '')}><Copy size={16} aria-hidden="true"/>复制邀请码
                        </button>
                    </div>
                </div>
                <div><label htmlFor="my-invite-link">邀请链接</label>
                    <div className="invitation-copy">
                        <input id="my-invite-link" readOnly value={link} onFocus={event => event.target.select()}/>
                        <button type="button" className="button button-secondary" onClick={() => void copy(link)}><Copy
                            size={16} aria-hidden="true"/>复制链接
                        </button>
                    </div>
                </div>
            </div>
            {notice && <p className="invitation-notice" role="status">{notice}</p>}
            <p className="invitation-help">仅注册时绑定邀请关系，注册后不能补填或更换。奖励不限次数；集中注册的奖励可能进入人工审核，审核通过后到账。</p>
            <h3>邀请与受邀记录</h3>
            {!records?.records.length ?
                <p className="invitation-empty">{loading ? '正在读取记录…' : '暂无记录，把邀请链接分享给朋友吧。'}</p>
                : <ul className="invitation-records">{records.records.map(record => <li key={record.id}>
                    <div>
                        <strong>{record.inviter ? '邀请了 ' : '受邀于 '}{record.counterpartyName || '已注销用户'}</strong>
                        <time>{record.createTime || '—'}</time>
                    </div>
                    <div><span
                        className={'invitation-status is-' + record.status.toLowerCase()}>{invitationStatusLabels[record.status]}</span>
                        <span>{record.status === 'PAID' ? '+' + record.points + ' 积分'
                            : record.status === 'PENDING' ? '待发 ' + record.points + ' 积分' : '未发放积分'}</span>
                    </div>
                </li>)}</ul>}
            <div className="invitation-pagination">
                <span>共 {records?.total ?? 0} 条记录</span>
                <button type="button" className="text-button" disabled={loading || page <= 1}
                        onClick={() => setPage(value => value - 1)}>上一页
                </button>
                <span>{page} / {records?.pages || 1}</span>
                <button type="button" className="text-button"
                        disabled={loading || !!error || page >= (records?.pages || 1)}
                        onClick={() => setPage(value => value + 1)}>下一页
                </button>
            </div>
        </>}
    </section>
}
