import {useRef, useState} from 'react'
import type {FormEvent} from 'react'
import {useNavigate} from 'react-router-dom'
import type {AppState} from '../App'
import {api, getToken} from '../lib/api'
import {passwordError} from '../lib/rules'
import {Modal} from './Modal'
import './password-editor.css'

export function PasswordEditor({app, onClose}: { app: AppState; onClose: () => void }) {
    const navigate = useNavigate()
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    const submitting = useRef(false)

    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        if (submitting.current) return
        const form = event.currentTarget
        const data = new FormData(form)
        const currentPassword = String(data.get('currentPassword') || '')
        const password = String(data.get('password') || '')
        const invalid = passwordError(currentPassword) || passwordError(password)
            || (password === currentPassword ? '新密码不能与当前密码相同' : '')
            || (password !== data.get('confirmPassword') ? '两次输入的新密码不一致' : '')
        if (invalid) {
            setError(invalid);
            return
        }
        const token = getToken()
        submitting.current = true
        setBusy(true)
        setError('')
        try {
            await api<void>('/auth/password', {method: 'POST', body: JSON.stringify({currentPassword, password})})
            // 请求期间若在其他标签页切换了账号，不能清理新账号的会话。
            if (getToken() !== token) return
            form.reset()
            app.passwordChanged()
            navigate('/login', {replace: true})
        } catch (reason) {
            if (getToken() === token) setError(reason instanceof Error ? reason.message : '修改失败，请重试')
        } finally {
            submitting.current = false
            setBusy(false)
        }
    }

    return <Modal title="修改密码" className="password-editor" onClose={() => {
        if (!submitting.current) onClose()
    }}>
        <p className="password-editor-note">修改成功后，所有设备均需使用新密码重新登录。</p>
        <form onSubmit={submit} noValidate aria-busy={busy}>
            <fieldset disabled={busy}>
                <div className="field"><label htmlFor="current-password">当前密码</label>
                    <input id="current-password" name="currentPassword" type="password" autoComplete="current-password"
                           placeholder="输入当前密码" minLength={6} maxLength={72} autoFocus required/>
                </div>
                <div className="field"><label htmlFor="new-password">新密码</label>
                    <input id="new-password" name="password" type="password" autoComplete="new-password"
                           placeholder="设置新密码，至少 6 位" minLength={6} maxLength={72} required/>
                </div>
                <div className="field"><label htmlFor="confirm-new-password">确认新密码</label>
                    <input id="confirm-new-password" name="confirmPassword" type="password" autoComplete="new-password"
                           placeholder="再次输入新密码" minLength={6} maxLength={72} required/>
                </div>
                {error && <p className="field-error" role="alert">{error}</p>}
                <div className="password-editor-actions">
                    <button type="button" className="button button-quiet" onClick={onClose}>取消</button>
                    <button type="submit" className="button button-primary">{busy ? '正在保存…' : '保存新密码'}</button>
                </div>
            </fieldset>
        </form>
    </Modal>
}
