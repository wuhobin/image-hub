import {useEffect, useRef, useState} from 'react'
import type {FormEvent, ReactNode} from 'react'
import {adminApi} from '../../lib/admin/api'
import type {InvitationSettings as Settings} from '../../lib/types'

/** 邀请开关与双方奖励一起保存，历史奖励金额不变。 */
export function InvitationSettings({tabs}: { tabs: ReactNode }) {
    const [settings, setSettings] = useState<Settings | null>(null)
    const [enabled, setEnabled] = useState(false)
    const [inviterPoints, setInviterPoints] = useState('')
    const [inviteePoints, setInviteePoints] = useState('')
    const [loading, setLoading] = useState(true)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState('')
    const [saved, setSaved] = useState(false)
    const [retry, setRetry] = useState(0)
    const submitting = useRef(false)
    const valid = [inviterPoints, inviteePoints].every(value => value.trim() !== '' && Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 2147483647)
    const changed = !!settings && valid && (enabled !== settings.enabled || Number(inviterPoints) !== settings.inviterPoints || Number(inviteePoints) !== settings.inviteePoints)

    function restore(value: Settings) {
        setEnabled(value.enabled)
        setInviterPoints(String(value.inviterPoints))
        setInviteePoints(String(value.inviteePoints))
    }

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void adminApi<Settings>('/settings/invitation', {signal: controller.signal})
            .then(result => {
                if (!controller.signal.aborted) {
                    setSettings(result);
                    restore(result)
                }
            })
            .catch(error => {
                if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '配置读取失败')
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [retry])

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        if (!changed || submitting.current) return
        submitting.current = true
        setSaving(true)
        setError('')
        setSaved(false)
        try {
            const result = await adminApi<Settings>('/settings/invitation', {
                method: 'PUT',
                body: JSON.stringify({
                    enabled,
                    inviterPoints: Number(inviterPoints),
                    inviteePoints: Number(inviteePoints)
                })
            })
            setSettings(result)
            restore(result)
            setSaved(true)
        } catch (error) {
            setError(error instanceof Error ? error.message : '保存失败')
        } finally {
            submitting.current = false;
            setSaving(false)
        }
    }

    return <section className="admin-settings-canvas" aria-label="邀请配置">
        <header className="admin-settings-toolbar">{tabs}
            <div className="admin-settings-actions">
                <button type="button" className="button button-secondary" disabled={!settings || saving}
                        onClick={() => {
                            if (settings) restore(settings)
                            setSaved(false)
                            setError('')
                        }}>撤销修改
                </button>
                <button type="submit" form="invitation-settings-form" className="button button-primary"
                        disabled={loading || !changed || saving}>{saving ? '正在保存…' : '保存修改'}</button>
            </div>
        </header>
        <div id="settings-panel-invitation" role="tabpanel" aria-labelledby="settings-tab-invitation" tabIndex={0}
             className="admin-settings-panel">
            {loading ? <div className="admin-settings-state" role="status">正在读取配置…</div>
                : !settings ? <div className="admin-settings-state"><p role="alert">{error}</p>
                        <button className="button button-secondary" onClick={() => setRetry(value => value + 1)}>重新加载
                        </button>
                    </div>
                    : <form id="invitation-settings-form" onSubmit={save} aria-busy={saving}>
                        <section className="admin-settings-section">
                            <div className="admin-settings-section-heading"><h2>邀请奖励</h2>
                                <p>好友完成邮箱验证并注册，双方获得额外积分。</p></div>
                            <div className="admin-settings-fields">
                                <label className="invitation-toggle"><input type="checkbox" checked={enabled}
                                                                            disabled={saving}
                                                                            onChange={event => {
                                                                                setEnabled(event.target.checked);
                                                                                setSaved(false)
                                                                            }}/>开启邀请奖励</label>
                                <label htmlFor="inviter-points">邀请人奖励</label>
                                <div className="admin-settings-input"><input id="inviter-points" type="number" min="1"
                                                                             max="2147483647" step="1" required
                                                                             disabled={saving}
                                                                             value={inviterPoints} onChange={event => {
                                    setInviterPoints(event.target.value);
                                    setSaved(false)
                                }}/><span>积分 / 人</span></div>
                                <label htmlFor="invitee-points">受邀新用户奖励</label>
                                <div className="admin-settings-input"><input id="invitee-points" type="number" min="1"
                                                                             max="2147483647" step="1" required
                                                                             disabled={saving}
                                                                             value={inviteePoints} onChange={event => {
                                    setInviteePoints(event.target.value);
                                    setSaved(false)
                                }}/><span>积分 / 人</span></div>
                                <p className="admin-settings-help">奖励不限次数。金额填写正整数，只影响保存之后注册的邀请。</p>
                                {error && <p className="admin-error" role="alert">{error}</p>}
                                {saved && <p className="admin-settings-saved" role="status">邀请配置已保存</p>}
                            </div>
                        </section>
                        <section className="admin-settings-section">
                            <div className="admin-settings-section-heading"><h2>发放与审核</h2>
                                <p>保留注册时的奖励金额。</p></div>
                            <div className="admin-settings-rules"><p>同一 IP、同一邀请人，10 分钟内第 3
                                个及之后成功注册的奖励进入人工审核，账号仍可正常使用。</p>
                                <p>关闭活动后，新的邀请注册不再产生奖励，开启后也不补发。已有待审核记录仍可通过或拒绝；通过时按注册时金额同时给双方发奖。</p>
                            </div>
                        </section>
                    </form>}
        </div>
    </section>
}
