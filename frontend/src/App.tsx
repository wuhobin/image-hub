import { lazy, Suspense, useEffect } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { ArrowUpRight, CheckCircle, ImageSquare, SignOut, WarningCircle, X } from '@phosphor-icons/react'
import Home from './pages/Home'
import History from './pages/History'
import Auth from './pages/Auth'
import { Modal } from './components/Modal'
import { ACCEPTED_TYPES, formatSize } from './lib/rules'
import { useImageHub } from './lib/useImageHub'

export const Starfield = lazy(() => import('./components/Starfield'))
export type AppState = ReturnType<typeof useImageHub>

function Header({ app }: { app: AppState }) {
  const location = useLocation()
  const authPage = ['/login', '/register'].includes(location.pathname)
  return <header className="header">
    <div className="header-inner">
      <Link className="brand" to="/" aria-label="Image Hub 首页">
        <span className="brand-mark"><ImageSquare size={23} weight="duotone" /></span>
        <span>image<span className="brand-light">hub</span><span className="brand-period">.</span></span>
      </Link>
      {!authPage && <nav className="navigation" aria-label="主导航">
        <NavLink to="/" end>上传图片</NavLink>
        <NavLink to="/history">上传记录</NavLink>
      </nav>}
      <div className="header-actions" aria-busy={app.initializing}>
        {/* 登录态尚未确认时保留占位，避免把空 user 提前当成游客。 */}
        {app.initializing ? <span className="session-placeholder" role="status" aria-label="正在恢复登录状态">
          <span className="avatar" aria-hidden="true" /><span className="session-placeholder-name" aria-hidden="true" />
        </span> : app.user ? <>
          <span className="user-name"><span className="avatar">{app.user.slice(0, 1).toUpperCase()}</span><span>{app.user}</span></span>
          <button className="icon-button logout-button" aria-label="退出登录" title="退出登录" onClick={app.logout}><SignOut size={19} /></button>
        </> : authPage
          ? <Link className="quiet-link" to="/">返回首页 <ArrowUpRight size={15} /></Link>
          : <><Link className="login-link" to="/login">登录</Link><Link className="button button-small button-header" to="/register">免费注册 <ArrowUpRight size={15} /></Link></>}
      </div>
    </div>
  </header>
}

export default function App() {
  const location = useLocation()
  const app = useImageHub(location.pathname === '/')
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' })
    const titles: Record<string, string> = { '/': '你的图片，即刻分享', '/history': '上传记录', '/login': '登录', '/register': '注册' }
    document.title = `${titles[location.pathname] || '页面未找到'} · Image Hub`
  }, [location.pathname])

  return <>
    <a className="skip-link" href="#main">跳到主要内容</a>
    <Header app={app} />
    <Routes>
      <Route path="/" element={<Home app={app} />} />
      <Route path="/history" element={app.initializing ? <main id="main" className="empty-state" role="status">正在恢复登录状态…</main> : app.user ? <History app={app} /> : <Navigate to="/login" state={{ from: '/history' }} replace />} />
      <Route path="/login" element={<Auth app={app} mode="login" />} />
      <Route path="/register" element={<Auth app={app} mode="register" />} />
      <Route path="*" element={<main id="main" className="not-found"><h1>这里还没有图片。</h1><p>页面不存在，回首页开始一次新的分享。</p><Link to="/" className="button button-primary">返回首页</Link></main>} />
    </Routes>
    <footer className="footer">
      <span className="footer-brand">imagehub.</span>
      <span>每一张图片，都有自己的去处。</span>
      <span className="demo-label">图片托管 · 即刻分享</span>
    </footer>
    <div className="toast-container" aria-live="polite" aria-atomic="true">
      {app.notice && <div className={`toast ${app.notice.error ? 'toast-error' : ''}`}>
        {app.notice.error ? <WarningCircle size={20} /> : <CheckCircle size={20} weight="fill" />}
        <span>{app.notice.text}</span>
        <button className="icon-button" aria-label="关闭提示" onClick={() => app.setNotice(null)}><X size={16} /></button>
      </div>}
    </div>
    {app.preview && <Modal title={app.preview.name} onClose={() => app.setPreview(null)} className="preview-modal">
      <div className="preview-image-wrap"><img src={app.preview.preview} alt={app.preview.name} /></div>
      <div className="preview-caption"><span>{app.preview.width} × {app.preview.height}</span><span>{formatSize(app.preview.size)}</span></div>
    </Modal>}
  </>
}

export function Ambient({ paused }: { paused: boolean }) {
  return <Suspense fallback={<div className="starfield" />}><Starfield paused={paused} /></Suspense>
}
export const fileAccept = ACCEPTED_TYPES.join(',')
