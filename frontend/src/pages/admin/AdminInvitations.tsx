import {useEffect, useRef, useState} from 'react'
import {useSearchParams} from 'react-router-dom'
import {adminApi} from '../../lib/admin/api'
import {invitationStatusLabels} from '../../lib/types'
import type {AdminInvitation, InvitationStatus, Page} from '../../lib/types'
import {AdminSelect} from './AdminSelect'
import {Modal} from '../../components/Modal'

/** 可疑邀请审核；页面显示注册时金额，服务器负责权限与防重。 */
export default function AdminInvitations() {
    const [params, setParams] = useSearchParams()
    const rawStatus = params.get('status') ?? 'PENDING'
    const status = rawStatus === '' || rawStatus in invitationStatusLabels ? rawStatus : 'PENDING'
    const requestedPage = Number(params.get('page') || 1)
    const page = Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1
    const [data, setData] = useState<Page<AdminInvitation> | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')
    const [revision, setRevision] = useState(0)
    const [decision, setDecision] = useState<{ record: AdminInvitation; approved: boolean } | null>(null)
    const [note, setNote] = useState('')
    const [reviewError, setReviewError] = useState('')
    const [busy, setBusy] = useState(false)
    const submitting = useRef(false)

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void adminApi<Page<AdminInvitation>>('/invitations?' + new URLSearchParams({
            status,
            page: String(page),
            pageSize: '20'
        }), {signal: controller.signal})
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
    }, [status, page, revision])

    function open(record: AdminInvitation, approved: boolean) {
        setDecision({record, approved})
        setNote('')
        setReviewError('')
    }

    async function review() {
        if (!decision || submitting.current) return
        submitting.current = true
        setBusy(true)
        setReviewError('')
        try {
            await adminApi('/invitations/' + decision.record.id + '/review', {
                method: 'POST',
                body: JSON.stringify({approved: decision.approved, note})
            })
            setNotice(decision.approved ? '审核通过，双方奖励已到账。' : '已拒绝此次双方奖励，账号保留。')
            setDecision(null)
            setRevision(value => value + 1)
        } catch (error) {
            setReviewError(error instanceof Error ? error.message : '审核结果未确认，请重试，不会重复发奖')
        } finally {
            submitting.current = false;
            setBusy(false)
        }
    }

    return <div className="admin-users-page">
        <div className="admin-page-title">
            <div><span className="admin-eyebrow">邀请与奖励</span><h1>邀请审核</h1>
                <p>核对集中注册记录，按注册时金额审核双方奖励。</p></div>
        </div>
        {notice && <p className="admin-settings-saved" role="status">{notice}</p>}
        <section className="admin-directory" aria-label="邀请记录">
            <div className="admin-toolbar">
                <AdminSelect id="invitation-status" label="奖励状态" value={status} options={[
                    {
                        value: '',
                        label: '全部状态'
                    }, ...Object.entries(invitationStatusLabels).map(([value, label]) => ({value, label})),
                ]} onChange={value => setParams({status: value, page: '1'})}/>
                <button className="button button-secondary" disabled={loading}
                        onClick={() => setRevision(value => value + 1)}>{loading ? '读取中…' : '刷新列表'}</button>
            </div>
            <div className="admin-table-scroll" aria-busy={loading} tabIndex={0} aria-label="邀请与审核明细">
                <table className="admin-table admin-invitation-table">
                    <thead>
                    <tr>
                        <th scope="col">邀请双方</th>
                        <th scope="col">奖励快照</th>
                        <th scope="col">来源与时间</th>
                        <th scope="col">状态与原因</th>
                        <th scope="col">审核</th>
                    </tr>
                    </thead>
                    <tbody>
                    {loading ? <tr>
                            <td colSpan={5}>
                                <div className="admin-table-state" role="status">正在读取邀请记录…</div>
                            </td>
                        </tr>
                        : error ? <tr>
                                <td colSpan={5}>
                                    <div className="admin-table-state" role="alert">{error}</div>
                                </td>
                            </tr>
                            : !data?.records.length ? <tr>
                                    <td colSpan={5}>
                                        <div className="admin-table-state">当前没有符合条件的邀请记录。</div>
                                    </td>
                                </tr>
                                : data.records.map(record => <tr key={record.id}>
                                    <td>
                                        <strong>{record.inviterName || '已注销用户'} → {record.inviteeName || '已注销用户'}</strong><small>用户 {record.inviterId} → {record.inviteeId} ·
                                        记录 {record.id}</small></td>
                                    <td>邀请人 +{record.inviterPoints}<small>新用户 +{record.inviteePoints} 积分</small>
                                    </td>
                                    <td>{record.registerIp}<small>{record.createTime || '—'}</small></td>
                                    <td>{invitationStatusLabels[record.status as InvitationStatus]}<small>{record.riskReason || (record.status === 'DISABLED' ? '注册时活动已暂停' : '正常邀请')}</small>
                                    </td>
                                    <td>{record.status === 'PENDING' ? <div className="admin-invitation-actions">
                                        <button className="button button-secondary button-small"
                                                onClick={() => open(record, true)}>通过
                                        </button>
                                        <button className="text-button" onClick={() => open(record, false)}>拒绝
                                        </button>
                                    </div> : record.reviewerId ? <>管理员 {record.reviewerId}<small>{record.reviewNote || '无备注'}</small><small>{record.updateTime}</small></> : '—'}</td>
                                </tr>)}
                    </tbody>
                </table>
            </div>
            <div className="admin-pagination"><span>共 {data?.total ?? 0} 条记录</span>
                <div>
                    <button className="text-button" disabled={loading || page <= 1}
                            onClick={() => setParams({status, page: String(page - 1)})}>上一页
                    </button>
                    <span>{page} / {data?.pages || 1}</span>
                    <button className="text-button" disabled={loading || !!error || page >= (data?.pages || 1)}
                            onClick={() => setParams({status, page: String(page + 1)})}>下一页
                    </button>
                </div>
            </div>
        </section>
        <p className="admin-directory-note">集中注册只代表可疑，不能仅凭同一 IP
            认定为小号。审核不修改账号，不改变注册时奖励金额。</p>
        {decision && <Modal title={decision.approved ? '通过邀请奖励' : '拒绝邀请奖励'} onClose={() => {
            if (!submitting.current) setDecision(null)
        }}>
            <form onSubmit={event => {
                event.preventDefault();
                void review()
            }} aria-busy={busy}>
                <p>{decision.record.inviterName || '已注销用户'} 邀请了 {decision.record.inviteeName || '已注销用户'}。</p>
                <p>{decision.approved ? '确认后邀请人获得 ' + decision.record.inviterPoints + ' 积分，新用户获得 ' + decision.record.inviteePoints + ' 积分。'
                    : '确认后双方均不获得此次邀请奖励，账号仍保留。'}审核结论不能撤销。</p>
                <div className="field"><label htmlFor="invitation-review-note">审核备注（选填，仅管理员可见）</label>
                    <textarea id="invitation-review-note" maxLength={255} value={note} disabled={busy}
                              onChange={event => setNote(event.target.value)} rows={3}/></div>
                {reviewError && <p className="field-error" role="alert">{reviewError}</p>}
                <div className="admin-invitation-actions">
                    <button type="button" className="button button-secondary" disabled={busy}
                            onClick={() => setDecision(null)}>取消
                    </button>
                    <button className="button button-primary"
                            disabled={busy}>{busy ? '处理中…' : decision.approved ? '确认通过并发奖' : '确认拒绝'}</button>
                </div>
            </form>
        </Modal>}
    </div>
}
