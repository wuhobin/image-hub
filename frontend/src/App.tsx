import {lazy, Suspense, useEffect, useRef, useState} from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { SignOut } from '@phosphor-icons/react/dist/csr/SignOut'
import { UserCircle } from '@phosphor-icons/react/dist/csr/UserCircle'
import Create from './pages/Create'
import HistoryLoading from './components/HistoryLoading'
import { useImageHub } from './lib/useImageHub'

const NoticeToast = lazy(() => import('./components/NoticeToast').then(module => ({default: module.NoticeToast})))
const ImagePreview = lazy(() => import('./components/ImagePreview').then(module => ({default: module.ImagePreview})))
const Profile = lazy(() => import('./pages/Profile'))
const History = lazy(() => import('./pages/History'))
const Upload = lazy(() => import('./pages/Home'))
const Auth = lazy(() => import('./pages/Auth'))
const Explore = lazy(() => import('./pages/Explore'))
const SharedCreation = lazy(() => import('./pages/SharedCreation'))
export type AppState = ReturnType<typeof useImageHub>

function Header({ app }: { app: AppState }) {
  const location = useLocation()
  const userMenu = useRef<HTMLDetailsElement>(null)
  const authPage = ['/login', '/register'].includes(location.pathname)
  useEffect(() => {
    if (userMenu.current) userMenu.current.open = false
    const closeOutside = (event: Event) => {
      const menu = userMenu.current
      if (menu?.open && !menu.contains(event.target as Node)) menu.open = false
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      const menu = userMenu.current
      if (event.key === 'Escape' && menu?.open) {
        menu.open = false
        menu.querySelector('summary')?.focus()
      }
    }
    document.addEventListener('pointerdown', closeOutside)
    document.addEventListener('focusin', closeOutside)
    document.addEventListener('keydown', closeOnEscape)
    return () => {
      document.removeEventListener('pointerdown', closeOutside)
      document.removeEventListener('focusin', closeOutside)
      document.removeEventListener('keydown', closeOnEscape)
    }
  }, [location.pathname])
  return <header className="header">
    <div className="header-inner">
      <Link className="brand" to="/" aria-label="ImgHub 首页">
        <span className="brand-mark"><ImageSquare size={23} weight="duotone" /></span>
        <span>Img<span className="brand-light">Hub</span></span>
      </Link>
      {!authPage && <nav className="navigation" aria-label="主导航">
        <NavLink to="/" end>AI 创作</NavLink>
          <NavLink to="/explore">作品广场</NavLink>
        <NavLink to="/upload">上传图片</NavLink>
        <NavLink to="/history">我的图片</NavLink>
      </nav>}
      <div className="header-actions" aria-busy={app.initializing}>
        {/* 登录态尚未确认时保留占位，避免把空 user 提前当成游客。 */}
        {app.initializing ? <span className="session-placeholder" role="status" aria-label="正在恢复登录状态">
          <span className="avatar" aria-hidden="true" /><span className="session-placeholder-name" aria-hidden="true" />
        </span> : app.user ? <details className="user-menu" ref={userMenu}
          onPointerEnter={event => {
            if (event.pointerType === 'mouse') event.currentTarget.open = true
          }}
          onPointerLeave={event => {
            if (event.pointerType === 'mouse' && !event.currentTarget.contains(document.activeElement)) event.currentTarget.open = false
          }}>
          <summary className="user-name" aria-label={`${app.user}，账户菜单`}>
            <span className="avatar" aria-hidden="true">{app.user.slice(0, 1).toUpperCase()}</span><span>{app.user}</span>
          </summary>
          <div className="user-dropdown">
            <NavLink className="user-menu-item" to="/profile" onClick={() => {
              if (userMenu.current) userMenu.current.open = false
            }}><UserCircle size={18} aria-hidden="true" />个人中心</NavLink>
            <button type="button" className="user-menu-item user-logout" onClick={() => {
              if (userMenu.current) userMenu.current.open = false
              userMenu.current?.querySelector('summary')?.focus()
              void app.logout()
            }}><SignOut size={18} aria-hidden="true" />退出登录</button>
          </div>
        </details> : authPage
          ? <Link className="quiet-link" to="/">返回首页 <ArrowUpRight size={15} /></Link>
          : <Link className="login-link" to="/login">登录</Link>}
      </div>
    </div>
  </header>
}

export default function App() {
  const location = useLocation()
  const app = useImageHub(location.pathname === '/upload', location.pathname === '/profile')
    const [noticesStarted, setNoticesStarted] = useState(false)
    // 首次消息才加载提示组件；之后保持挂载，让消息清空时原有退出动画完整执行。
    useEffect(() => {
        if (app.notice) setNoticesStarted(true)
    }, [app.notice])
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'instant' })
      const titles: Record<string, string> = {
          '/': 'AI 创作',
          '/create': 'AI 创作',
          '/upload': '上传图片',
          '/history': '我的图片',
          '/profile': '个人中心',
          '/creations': '创作记录',
          '/explore': '作品广场',
          '/login': '登录',
          '/register': '注册'
      }
      document.title = `ImgHub · ${location.pathname.startsWith('/share/') ? '分享作品' : titles[location.pathname] || '页面未找到'}`
  }, [location.pathname])

  return <>
    <a className="skip-link" href="#main">跳到主要内容</a>
    <Header app={app} />
    <Suspense fallback={location.pathname === '/history' ? <HistoryLoading /> : <main id="main" className="empty-state" role="status">正在加载页面…</main>}>
    <Routes>
        <Route path="/" element={app.initializing ?
            <main id="main" className="creation-page hero creation-loading" aria-busy="true">
                <div className="starfield ambient-layer" aria-hidden="true"/>
                <p role="status">正在恢复创作空间…</p></main> :
            <Create key={app.user ? 'user:' + app.user : 'guest'} app={app}/>}/>
        <Route path="/creations" element={app.initializing ?
            <main id="main" className="empty-state" role="status">正在恢复创作记录…</main> : app.user ?
                <Create key={'history:' + app.user} app={app} view="history"/> :
                <Navigate to="/login" state={{from: '/creations'}} replace/>}/>
      <Route path="/create" element={<Navigate to="/" state={location.state} replace />} />
        <Route path="/explore" element={<Explore/>}/>
        <Route path="/share/:shareId" element={<SharedCreation key={location.pathname} app={app}/>}/>
      <Route path="/upload" element={<Upload app={app} />} />
      <Route path="/history" element={app.initializing ? <HistoryLoading /> : app.user ? <History app={app} /> : <Navigate to="/login" state={{ from: '/history' }} replace />} />
      <Route path="/profile" element={app.initializing || app.user ? <Profile app={app} /> : <Navigate to="/login" state={{ from: '/profile' }} replace />} />
      <Route path="/login" element={<Auth app={app} mode="login" />} />
      <Route path="/register" element={<Auth app={app} mode="register" />} />
      <Route path="*" element={<main id="main" className="not-found"><h1>这里还没有图片。</h1><p>页面不存在，回首页开始一次新的创作。</p><Link to="/" className="button button-primary">返回首页</Link></main>} />
    </Routes>
    </Suspense>
    <footer className="footer">
      <span className="footer-brand">ImgHub</span>
      <span>从一个想法，到一张图片。</span>
      <span className="demo-label">AI 创作 · 图片分享</span>
    </footer>
      {noticesStarted &&
          <Suspense fallback={null}><NoticeToast notice={app.notice} onDismiss={() => app.setNotice(null)}/></Suspense>}
      {app.preview &&
          <Suspense fallback={null}><ImagePreview key={app.preview.url ?? app.preview.preview} image={app.preview}
                                                  onClose={() => app.setPreview(null)}/></Suspense>}
  </>
}
