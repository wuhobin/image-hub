import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ArrowRight, ArrowUpRight, Check, Eye, EyeSlash, ImageSquare, LinkSimple } from '@phosphor-icons/react'
import { Ambient } from '../App'
import type { AppState } from '../App'
import { passwordError } from '../lib/rules'
import { api } from '../lib/api'

export default function Auth({ app, mode }: { app: AppState; mode: 'login' | 'register' }) {
  const navigate = useNavigate()
  const location = useLocation()
  const registering = mode === 'register'
  const destination = location.state?.from === '/history' ? '/history' : '/'
  const [showPassword, setShowPassword] = useState(false)
  const [email, setEmail] = useState('')
  const [sentEmail, setSentEmail] = useState('')
  const [countdown, setCountdown] = useState(0)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [sending, setSending] = useState(false)
  const requestVersion = useRef(0)
  const submitting = useRef(false)
  const sendingCode = useRef(false)
  const form = useRef<HTMLFormElement>(null)

  useEffect(() => {
    form.current?.reset()
    setErrors({})
    setEmail('')
    setSentEmail('')
    setCountdown(0)
    setLoading(false)
    requestVersion.current++
    submitting.current = false
    sendingCode.current = false
    setSending(false)
    return () => { requestVersion.current++ }
  }, [mode])
  useEffect(() => {
    if (countdown <= 0) return
    const timer = setTimeout(() => setCountdown(value => value - 1), 1000)
    return () => clearTimeout(timer)
  }, [countdown])

  if (app.user) return <Navigate to={destination} replace />

  async function sendCode() {
    if (sendingCode.current || countdown > 0) return
    const normalized = email.trim()
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalized)) {
      setErrors(current => ({ ...current, email: '请填写有效的邮箱地址' }))
      form.current?.querySelector<HTMLInputElement>('[name="email"]')?.focus()
      return
    }
    setErrors(current => ({ ...current, email: '', code: '' }))
    sendingCode.current = true
    setSending(true)
    const version = requestVersion.current
    try {
      await api<void>('/auth/email-code', { method: 'POST', body: JSON.stringify({ email: normalized }) })
      if (version !== requestVersion.current) return
      setSentEmail(normalized)
      setCountdown(60)
      app.notify('验证码已发送，请查收邮件')
    } catch (error) {
      if (version === requestVersion.current) setErrors(current => ({ ...current, code: error instanceof Error ? error.message : '发送失败，请重试' }))
    } finally {
      if (version === requestVersion.current) { sendingCode.current = false; setSending(false) }
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || app.initializing) return
    const data = new FormData(event.currentTarget)
    const username = String(data.get('username') || '').trim()
    const password = String(data.get('password') || '')
    const next: Record<string, string> = {}
    if (username.length < 3 || username.length > 32) next.username = '用户名需要 3–32 个字符'
    else if (/\s/.test(username)) next.username = '用户名不能包含空白字符'
    if (passwordError(password)) next.password = passwordError(password)
    if (registering) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) next.email = '请填写有效的邮箱地址'
      if (sentEmail !== email.trim() || !sentEmail) next.code = '请先获取当前邮箱的验证码'
      else if (!/^[0-9]{6}$/.test(String(data.get('code')))) next.code = '请输入 6 位数字验证码'
      if (String(data.get('confirmPassword')) !== password) next.confirmPassword = '两次输入的密码不一致'
    }
    setErrors(next)
    if (Object.keys(next).length) {
      form.current?.querySelector<HTMLInputElement>(`[name="${Object.keys(next)[0]}"]`)?.focus()
      return
    }
    setLoading(true)
    submitting.current = true
    const version = requestVersion.current
    try {
      if (registering) {
        await api<void>('/auth/register', { method: 'POST', body: JSON.stringify({ username, email: email.trim(), password, code: String(data.get('code')) }) })
        if (version !== requestVersion.current) return
        app.notify('注册成功，请输入用户名和密码登录')
        navigate('/login', { replace: true, state: { from: destination, username } })
      } else {
        await app.authenticate(username, password)
        if (version !== requestVersion.current) return
        app.notify('登录成功，继续你的分享')
        navigate(destination, { replace: true })
      }
    } catch (error) {
      if (version === requestVersion.current) setErrors(current => ({ ...current, form: error instanceof Error ? error.message : '请求失败，请重试' }))
    } finally {
      if (version === requestVersion.current) { submitting.current = false; setLoading(false) }
    }
  }

  const error = (name: string) => errors[name] ? <span id={`${name}-error`} className="field-error" role="alert">{errors[name]}</span> : null

  return <main id="main" className={`auth-page ${registering ? 'auth-register' : ''}`}>
    <section className="auth-story" aria-label="Image Hub">
      <Ambient paused={app.paused} />
      <div className="auth-story-content">
        <div className="auth-orbit" aria-hidden="true"><div className="orbit-ring ring-one" /><div className="orbit-ring ring-two" /><div className="orbit-ring ring-three" /><div className="orbit-core"><ImageSquare size={47} weight="light" /></div><span className="orbit-link"><LinkSimple size={22} /></span></div>
        <span className="section-kicker">图片不止于收藏</span>
        <h1>让好图片，<br />抵达更多地方。</h1>
        <p>留住一瞬间，分享一整个世界。<br />你的下一次分享，从这里开始。</p>
        <div className="auth-benefits"><span><Check size={15} />轻松上传</span><span><Check size={15} />一键外链</span><span><Check size={15} />有序管理</span></div>
      </div>
    </section>
    <section className="auth-form-section page-enter">
      <div className="auth-form-wrap">
        <span className="section-kicker">{registering ? '开始你的第一次分享' : '很高兴再次见到你'}</span>
        <h2>{registering ? '创建你的账号' : '欢迎回来。'}</h2>
        <p className="auth-switch">{registering ? '已经有账号？' : '还没有账号？'} <Link to={registering ? '/login' : '/register'} state={location.state}>{registering ? '去登录' : '免费注册'} <ArrowUpRight size={13} /></Link></p>
        {app.pending.length > 0 && <p className="pending-notice"><ImageSquare size={16} />已为你保留 {app.pending.length} 张待上传图片</p>}
        <form ref={form} onSubmit={submit} noValidate>
          <div className="field"><label htmlFor="username">用户名</label><input id="username" name="username" defaultValue={registering ? '': location.state?.username || ''} placeholder={registering ? '设置用户名，3–32 个字符' : '请输入用户名'} autoComplete="username" minLength={3} maxLength={32} aria-invalid={!!errors.username} aria-describedby={errors.username ? 'username-error' : undefined} />{error('username')}</div>
          {registering && <>
            <div className="field"><label htmlFor="email">邮箱地址</label><input id="email" type="email" name="email" value={email} onChange={event => setEmail(event.target.value)} placeholder="you@example.com" autoComplete="email" aria-invalid={!!errors.email} aria-describedby={errors.email ? 'email-error' : undefined} />{error('email')}</div>
            <div className="field"><label htmlFor="code">邮箱验证码</label><div className="code-field"><input id="code" name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="6 位数字验证码" aria-invalid={!!errors.code} aria-describedby={errors.code ? 'code-error' : undefined} /><button type="button" disabled={sending || countdown > 0} onClick={sendCode}>{sending ? '发送中…' : countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}</button></div>{error('code')}{sentEmail && !errors.code && <span className="field-help">验证码已发送，有效期 5 分钟</span>}</div>
          </>}
          <div className="field"><label htmlFor="password">密码</label><div className="password-field"><input id="password" name="password" type={showPassword ? 'text' : 'password'} placeholder={registering ? '设置密码，至少 6 位' : '请输入密码'} autoComplete={registering ? 'new-password' : 'current-password'} aria-invalid={!!errors.password} aria-describedby={errors.password ? 'password-error' : undefined} /><button type="button" className="icon-button" aria-label={showPassword ? '隐藏密码' : '显示密码'} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeSlash size={19} /> : <Eye size={19} />}</button></div>{error('password')}</div>
          {registering && <div className="field"><label htmlFor="confirmPassword">确认密码</label><input id="confirmPassword" name="confirmPassword" type={showPassword ? 'text' : 'password'} placeholder="再次输入密码" autoComplete="new-password" aria-invalid={!!errors.confirmPassword} aria-describedby={errors.confirmPassword ? 'confirmPassword-error' : undefined} />{error('confirmPassword')}</div>}
          {error('form')}
          <button className="button button-primary auth-submit" type="submit" disabled={loading || app.initializing}>{loading ? '正在进入…' : registering ? '创建账号' : '登录'}<ArrowRight size={18} /></button>
        </form>
        <p className="auth-demo-note">{registering ? '注册成功后，请使用用户名和密码登录。' : '登录有效期为 3 天，请妥善保管账号密码。'}</p>
      </div>
    </section>
  </main>
}
