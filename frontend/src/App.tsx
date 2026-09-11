import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { ArrowUpRight, CheckCircle, ImageSquare, SignOut, WarningCircle, X } from '@phosphor-icons/react'
import Home from './pages/Home'
import History from './pages/History'
import Auth from './pages/Auth'
import { Modal } from './components/Modal'
import { ACCEPTED_TYPES, fileError, formatSize, MAX_FILES } from './lib/rules'
import { demoRecords } from './lib/mock'
import type { ImageRecord, PendingImage } from './lib/mock'

export const Starfield = lazy(() => import('./components/Starfield'))
type Preview = Pick<ImageRecord, 'name' | 'preview' | 'size' | 'width' | 'height'>
type Notice = { text: string; error: boolean }

function useDemo() {
  const [user, setUser] = useState<string | null>(null)
  const [records, setRecords] = useState<ImageRecord[]>([])
  const [pending, setPending] = useState<PendingImage[]>([])
  const [preview, setPreview] = useState<Preview | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [paused, setPaused] = useState(false)
  const ownedUrls = useRef(new Set<string>())
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const uploadTimer = useRef<ReturnType<typeof setInterval> | undefined>(undefined)
  const selectionLock = useRef(false)
  const generation = useRef(0)
  const busy = pending.some(item => item.status === 'uploading')

  useEffect(() => () => {
    generation.current++
    clearTimeout(noticeTimer.current)
    clearInterval(uploadTimer.current)
    ownedUrls.current.forEach(url => URL.revokeObjectURL(url))
    ownedUrls.current.clear()
  }, [])

  function notify(text: string, error = false) {
    clearTimeout(noticeTimer.current)
    setNotice({ text, error })
    noticeTimer.current = setTimeout(() => setNotice(null), 4200)
  }

  function release(url: string) {
    if (ownedUrls.current.delete(url)) URL.revokeObjectURL(url)
  }

  async function selectFiles(files: FileList | File[]) {
    if (selectionLock.current || busy) return
    selectionLock.current = true
    setSelecting(true)
    const selected: PendingImage[] = []
    const errors: string[] = []
    const version = generation.current
    const available = MAX_FILES - pending.length
    if (files.length > available) errors.push(`最多选择 ${MAX_FILES} 张图片`)
    try {
      for (const file of Array.from(files).slice(0, available)) {
        const error = fileError(file)
        if (error) { errors.push(`${file.name}：${error}`); continue }
        const url = URL.createObjectURL(file)
        ownedUrls.current.add(url)
        try {
          const img = new Image()
          img.src = url
          await img.decode()
          if (version !== generation.current) { release(url); continue }
          selected.push({
            id: crypto.randomUUID(), file, preview: url, width: img.naturalWidth, height: img.naturalHeight,
            progress: 0, status: 'ready',
          })
        } catch {
          release(url)
          errors.push(`${file.name} 无法读取，请换一张图片`)
        }
      }
      if (version !== generation.current) return
      setPending(current => [...current, ...selected])
      if (errors.length) notify(errors[0], true)
    } finally {
      selectionLock.current = false
      setSelecting(false)
    }
  }

  function removePending(id: string) {
    if (busy) return
    const item = pending.find(image => image.id === id)
    if (item && !records.some(image => image.id === id)) release(item.preview)
    setPending(current => current.filter(image => image.id !== id))
  }

  function clearPending() {
    if (busy) return
    pending.forEach(item => { if (!records.some(image => image.id === item.id)) release(item.preview) })
    setPending([])
  }

  function upload() {
    if (!user || busy || selecting) return
    const items = pending.filter(item => item.status === 'ready')
    if (!items.length) return
    const ids = new Set(items.map(item => item.id))
    setPending(current => current.map(item => ids.has(item.id) ? { ...item, status: 'uploading', progress: 0 } : item))
    let progress = 0
    // ponytail: one in-memory batch for the visual demo; use per-file API results when the backend is connected.
    uploadTimer.current = setInterval(() => {
      progress = Math.min(100, progress + 7)
      setPending(current => current.map(item => ids.has(item.id) ? { ...item, progress, status: progress === 100 ? 'done' : 'uploading' } : item))
      if (progress === 100) {
        clearInterval(uploadTimer.current)
        const uploaded = items.map(item => ({
          id: item.id, name: item.file.name, preview: item.preview, size: item.file.size,
          width: item.width, height: item.height, createdAt: new Date().toISOString(),
          type: item.file.type.split('/')[1].replace('jpeg', 'jpg').toUpperCase(),
          url: `https://cdn.imagehub.example/images/${item.id}.${item.file.type.split('/')[1].replace('jpeg', 'jpg')}`,
        }))
        setRecords(current => [...uploaded, ...current])
        notify(`${items.length} 张图片已完成模拟上传`)
      }
    }, 100)
  }

  function authenticate(username: string) {
    setUser(username)
    setRecords(demoRecords())
  }

  function logout() {
    generation.current++
    clearInterval(uploadTimer.current)
    setUser(null)
    setRecords([])
    setPending([])
    setPreview(null)
    ownedUrls.current.forEach(url => URL.revokeObjectURL(url))
    ownedUrls.current.clear()
    notify('已退出登录')
  }

  function deleteRecord(id: string) {
    const record = records.find(item => item.id === id)
    if (!record) return
    setRecords(current => current.filter(item => item.id !== id))
    setPending(current => current.filter(item => item.id !== id))
    setPreview(null)
    release(record.preview)
    notify('图片和上传记录已删除（演示）')
  }

  async function copyUrl(url: string) {
    try {
      await navigator.clipboard.writeText(url)
      notify('演示链接已复制')
    } catch {
      notify('复制失败，请选中链接后手动复制', true)
    }
  }

  return {
    user, records, pending, preview, setPreview, notice, setNotice, selecting, paused, setPaused,
    busy, selectFiles, removePending, clearPending, upload, authenticate, logout, deleteRecord, copyUrl, notify,
  }
}
export type AppState = ReturnType<typeof useDemo>

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
      <div className="header-actions">
        {app.user ? <>
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
  const app = useDemo()
  const location = useLocation()
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
      <Route path="/history" element={app.user ? <History app={app} /> : <Navigate to="/login" state={{ from: '/history' }} replace />} />
      <Route path="/login" element={<Auth app={app} mode="login" />} />
      <Route path="/register" element={<Auth app={app} mode="register" />} />
      <Route path="*" element={<main id="main" className="not-found"><h1>这里还没有图片。</h1><p>页面不存在，回首页开始一次新的分享。</p><Link to="/" className="button button-primary">返回首页</Link></main>} />
    </Routes>
    <footer className="footer">
      <span className="footer-brand">imagehub.</span>
      <span>每一张图片，都有自己的去处。</span>
      <span className="demo-label">交互演示 · 数据不保存</span>
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

