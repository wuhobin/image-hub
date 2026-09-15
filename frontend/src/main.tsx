import { lazy, StrictMode, Suspense } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, useLocation } from 'react-router-dom'
import App from './App'
import './styles.css'

const AdminApp = lazy(() => import('./pages/admin/AdminApp'))
function Entry() {
  const path = useLocation().pathname
  // 管理入口不挂载普通用户状态，两个 Token 与失效事件互不影响。
  return path === '/admin' || path.startsWith('/admin/')
    ? <Suspense fallback={<main className="empty-state" role="status">正在打开管理后台…</main>}><AdminApp /></Suspense>
    : <App />
}

createRoot(document.getElementById('root')!).render(
  <StrictMode><BrowserRouter><Entry /></BrowserRouter></StrictMode>,
)
