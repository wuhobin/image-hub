import { useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { Eye } from '@phosphor-icons/react/dist/csr/Eye'
import { EyeSlash } from '@phosphor-icons/react/dist/csr/EyeSlash'
import { Users } from '@phosphor-icons/react/dist/csr/Users'
import { Ambient } from '../../components/Ambient'
import { ADMIN_SESSION, adminApi, getAdminToken } from '../../lib/admin/api'
import type { Admin } from '../../lib/admin/api'
import AdminLayout from './AdminLayout'
import AdminUsers from './AdminUsers'
import AdminSettings from './AdminSettings'
import AdminModels from './AdminModels'
import AdminCreations from './AdminCreations'
import './admin.css'

const message = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试'

export default function AdminApp() {
  const [token, setToken] = useState(getAdminToken)
  const [admin, setAdmin] = useState<Admin | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [sessionError, setSessionError] = useState('')
  const [retry, setRetry] = useState(0)
  const [loggingOut, setLoggingOut] = useState(false)
  const location = useLocation()
  const navigate = useNavigate()

  useEffect(() => {
    const sync = () => setToken(getAdminToken())
    window.addEventListener('storage', sync)
    window.addEventListener(ADMIN_SESSION.expiredEvent, sync)
    return () => {
      window.removeEventListener('storage', sync)
      window.removeEventListener(ADMIN_SESSION.expiredEvent, sync)
    }
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setAdmin(null)
    setSessionError('')
    setInitializing(!!token)
    if (token) void adminApi<Admin>('/auth/me', { signal: controller.signal })
      .then(setAdmin)
      .catch(error => { if (!controller.signal.aborted) setSessionError(message(error)) })
      .finally(() => { if (!controller.signal.aborted) setInitializing(false) })
    return () => controller.abort()
  }, [token, retry])
  useEffect(() => { if (!admin) document.title = 'ImgHub · 管理员登录' }, [admin])

  const logout = async () => {
    if (loggingOut) return
    setLoggingOut(true)
    try {
      await adminApi('/auth/logout', { method: 'POST' })
      localStorage.removeItem(ADMIN_SESSION.tokenKey)
      setToken(null)
      setAdmin(null)
      navigate('/admin/login', { replace: true })
    } catch (error) { setSessionError(message(error)) }
    finally { setLoggingOut(false) }
  }

  if (initializing) return <AdminPublicLayout><main id="admin-main" className="admin-state" role="status">正在确认管理员身份…</main></AdminPublicLayout>
  if (token && !admin) return <AdminPublicLayout><main id="admin-main" className="admin-state"><p role="alert">{sessionError || '无法确认登录状态'}</p><button className="button button-primary" onClick={() => setRetry(value => value + 1)}>重新连接</button><button className="quiet-link admin-text-button" onClick={() => { localStorage.removeItem(ADMIN_SESSION.tokenKey); setToken(null) }}>返回登录</button></main></AdminPublicLayout>

  return <Routes>
    <Route path="/admin/login" element={admin ? <Navigate to="/admin/users" replace /> : <AdminPublicLayout><AdminLogin onLogin={value => { setToken(value); navigate('/admin/users', { replace: true }) }} /></AdminPublicLayout>} />
    <Route path="/admin" element={admin ? <AdminLayout admin={admin} loggingOut={loggingOut} logout={() => void logout()} error={sessionError} /> : <Navigate to="/admin/login" replace />}>
      <Route index element={<Navigate to={'users' + location.search} replace />} />
      <Route path="users" element={<AdminUsers />} />
      <Route path="settings" element={<AdminSettings />} />
      <Route path="models" element={<AdminModels />} />
        <Route path="creations" element={<AdminCreations/>}/>
      <Route path="*" element={<div className="admin-state"><h1>页面未找到</h1><p>这个管理页面不存在。</p><Link className="button button-primary" to="/admin/users">返回用户管理</Link></div>} />
    </Route>
  </Routes>
}

function AdminPublicLayout({ children }: { children: ReactNode }) {
  return <div className="admin-shell">
    <a className="skip-link" href="#admin-main">跳到主要内容</a>
    <header className="admin-header">
      <Link className="brand" to="/admin" aria-label="ImgHub 管理首页"><span className="brand-mark"><ImageSquare size={23} weight="duotone" /></span><span>Img<span className="brand-light">Hub</span></span></Link>
      <span className="admin-header-label">管理空间</span>
      <Link className="quiet-link admin-public-return" to="/">返回网站 <ArrowUpRight size={15} /></Link>
    </header>
    {children}
    <footer className="admin-footer"><span>ImgHub · 管理空间</span><span>让每一次分享，都有迹可寻。</span></footer>
  </div>
}

function AdminLogin({ onLogin }: { onLogin: (token: string) => void }) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [show, setShow] = useState(false)
  const submitting = useRef(false)
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const data = new FormData(form)
    const username = String(data.get('username') || '').trim()
    const password = String(data.get('password') || '')
    if (new TextEncoder().encode(password).length > 72) { setError('密码长度不能超过 72 个 UTF-8 字节'); return }
    submitting.current = true
    setLoading(true)
    setError('')
    try {
      const result = await adminApi<{ token: string }>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
      localStorage.setItem(ADMIN_SESSION.tokenKey, result.token)
      form.reset()
      onLogin(result.token)
    } catch (error) { setError(message(error)) }
    finally { submitting.current = false; setLoading(false) }
  }
  return <main id="admin-main" className="admin-login">
    <section className="admin-login-story" aria-label="ImgHub 管理空间">
      <Ambient />
      <div className="admin-eyebrow"><span /> IMG HUB / PEOPLE</div>
      <h1>每一次分享，<br />从一个人开始。</h1>
      <p>在这里，认识你的用户。<br />让图片之外的连接，也清晰可见。</p>
      <div className="admin-orbit" aria-hidden="true"><div><Users size={45} weight="duotone" /></div><i /><b /></div>
      <span className="admin-story-caption">连接图片，也连接分享的人。</span>
    </section>
    <section className="admin-login-panel">
      <div className="admin-login-heading"><span className="admin-eyebrow">管理入口</span><h2>欢迎回来。</h2><p>使用管理员账号，进入你的工作空间。</p></div>
      <form onSubmit={submit} className="admin-login-form" aria-busy={loading}>
        <label htmlFor="admin-username">管理员账号</label>
        <input id="admin-username" name="username" autoComplete="username" placeholder="输入管理员账号" minLength={3} maxLength={32} required disabled={loading} />
        <label htmlFor="admin-password">密码</label>
        <div className="admin-password"><input id="admin-password" name="password" type={show ? 'text' : 'password'} autoComplete="current-password" placeholder="输入密码" minLength={6} maxLength={72} required disabled={loading} /><button type="button" className="icon-button" onClick={() => setShow(value => !value)} aria-label={show ? '隐藏密码' : '显示密码'}>{show ? <EyeSlash size={19} /> : <Eye size={19} />}</button></div>
        {error && <p className="admin-error" role="alert">{error}</p>}
        <button className="button button-primary admin-login-submit" disabled={loading}>{loading ? '正在登录…' : '进入管理空间'}<ArrowRight size={18} /></button>
      </form>
      <p className="admin-login-note">仅向管理员开放登录。普通用户请前往<Link to="/login">用户登录 <ArrowUpRight size={12} /></Link></p>
    </section>
  </main>
}
