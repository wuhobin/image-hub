import {useEffect, useRef, useState} from 'react'
import {Check} from '@phosphor-icons/react/dist/csr/Check'
import {Gift} from '@phosphor-icons/react/dist/csr/Gift'
import {api} from '../lib/api'
import type {CheckIn} from '../lib/types'

/** 状态只读加载；主动点击才领取，跨天和重新聚焦时更新资格。 */
export function CheckInCard({onClaimed}: { onClaimed: () => void }) {
    const [status, setStatus] = useState<CheckIn | null>(null)
    const [loading, setLoading] = useState(true)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const [notice, setNotice] = useState('')
    const pending = useRef(false)
    const sequence = useRef(0)
    const request = useRef<AbortController | null>(null)

    async function load() {
        if (pending.current) return
        request.current?.abort()
        const controller = new AbortController()
        request.current = controller
        const current = ++sequence.current
        setLoading(true)
        setError('')
        try {
            const result = await api<CheckIn>('/check-in', {signal: controller.signal})
            if (current === sequence.current) setStatus(result)
        } catch (error) {
            if (!controller.signal.aborted && current === sequence.current) setError(error instanceof Error ? error.message : '签到状态读取失败')
        } finally {
            if (current === sequence.current) setLoading(false)
        }
    }

    useEffect(() => {
        void load()
        const refresh = () => {
            if (!document.hidden) void load()
        }
        window.addEventListener('focus', refresh)
        document.addEventListener('visibilitychange', refresh)
        return () => {
            sequence.current++
            request.current?.abort()
            window.removeEventListener('focus', refresh)
            document.removeEventListener('visibilitychange', refresh)
        }
    }, [])

    useEffect(() => {
        if (!status) return
        const timer = setTimeout(() => {
            setNotice('');
            void load()
        }, Math.max(1000, status.nextResetAt - Date.now() + 100))
        return () => clearTimeout(timer)
    }, [status])

    async function claim() {
        if (!status || status.signedIn || loading || pending.current) return
        pending.current = true
        request.current?.abort()
        const controller = new AbortController()
        request.current = controller
        const current = ++sequence.current
        setBusy(true)
        setError('')
        setNotice('')
        try {
            const result = await api<CheckIn>('/check-in', {method: 'POST', signal: controller.signal})
            if (current !== sequence.current) return
            setStatus(result)
            setNotice('今日签到奖励 ' + result.rewardPoints + ' 积分已到账')
            onClaimed()
        } catch (error) {
            if (!controller.signal.aborted && current === sequence.current) setError(error instanceof Error ? error.message : '签到结果未确认，请重试，不会重复发放')
        } finally {
            pending.current = false
            if (current === sequence.current) setBusy(false)
        }
    }

    const days = status?.consecutiveDays ?? 0
    const progress = status?.signedIn && days % 7 === 0 ? 7 : days % 7
    return <section className="profile-check-in" aria-labelledby="check-in-title" aria-busy={loading || busy}>
        <div className="check-in-summary">
            <h2 id="check-in-title"><Gift size={19} aria-hidden="true"/>每日签到</h2>
            <p>连续签到 <strong>{status ? days : '—'}</strong> 天</p>
            <span>每天 +{status?.dailyPoints ?? '—'} 积分 · 每满 7 天额外 <b>+{status?.bonusPoints ?? '—'}</b></span>
        </div>
        <ol className="check-in-progress" aria-label="本轮七天签到进度">
            {Array.from({length: 7}, (_, index) => <li key={index}
                                                       aria-current={status && !status.signedIn && index === progress ? 'step' : undefined}
                                                       className={(index < progress ? 'is-done' : '') + (index === 6 ? ' is-bonus' : '')}>
                <span className="check-in-day">{index < progress ?
                    <Check size={18} weight="bold" aria-label="已完成"/> : index === 6 ?
                        <Gift size={18} aria-hidden="true"/> : index + 1}</span>
                <span>第 {index + 1} 天</span>
            </li>)}
        </ol>
        <div className="check-in-action">
            <button type="button" className="button check-in-button"
                    disabled={!status || loading || busy || status.signedIn} onClick={() => void claim()}>
                {busy ? '领取中…' : loading && !status ? '读取中…' : status?.signedIn ? <><Check size={17}
                                                                                                 aria-hidden="true"/>今日已签到</> : '签到领 ' + (status?.rewardPoints ?? '—') + ' 积分'}
            </button>
            <span>北京时间 00:00 重置 · 漏签重新累计</span>
        </div>
        {error && <p className="check-in-message is-error" role="alert">{error}
            <button type="button" className="text-button" disabled={busy} onClick={() => void load()}>刷新状态</button>
        </p>}
        {notice && <p className="check-in-message" role="status">{notice}</p>}
    </section>
}
