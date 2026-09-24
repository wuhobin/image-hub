import { useEffect, useRef } from 'react'
import type {ReactNode} from 'react'
import './admin.css'
import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { CaretRight } from '@phosphor-icons/react/dist/csr/CaretRight'
import { SignOut } from '@phosphor-icons/react/dist/csr/SignOut'
import { Users } from '@phosphor-icons/react/dist/csr/Users'
import { SlidersHorizontal } from '@phosphor-icons/react/dist/csr/SlidersHorizontal'
import { List } from '@phosphor-icons/react/dist/csr/List'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { X } from '@phosphor-icons/react/dist/csr/X'
import type { Admin } from '../../lib/admin/api'

// 这里只列出已上线的页面；新增模块与 AdminApp 中的子路由一同接入。
const navigation = [
    {to: '/admin/templates', label: '模板管理', description: '场景、提示词与分类', icon: Sparkle},
    {to: '/admin/users', label: '用户管理', description: '账户与积分', icon: Users},
    {to: '/admin/invitations', label: '邀请审核', description: '注册邀请与奖励', icon: Users},
    {to: '/admin/settings', label: '配置管理', description: '平台规则与奖励', icon: SlidersHorizontal},
    {to: '/admin/models', label: '模型管理', description: '生图服务与参数', icon: Sparkle},
    {to: '/admin/creations', label: '作品分享', description: '公开内容与展示', icon: ImageSquare},
]

type LayoutProps = { admin: Admin | null; loggingOut: boolean; logout: () => void; error: string; children?: ReactNode }

export default function AdminLayout({admin, loggingOut, logout, error, children}: LayoutProps) {
  const location = useLocation()
  const drawer = useRef<HTMLDialogElement>(null)
  const title = navigation.find(item => location.pathname.replace(/\/$/, '') === item.to)?.label || '管理后台'
  useEffect(() => {
    drawer.current?.close()
    document.title = 'ImgHub · ' + title
  }, [location.pathname, title])
  useEffect(() => {
    const desktop = matchMedia('(min-width: 901px)')
    const closeOnDesktop = () => { if (desktop.matches) drawer.current?.close() }
    desktop.addEventListener('change', closeOnDesktop)
    return () => desktop.removeEventListener('change', closeOnDesktop)
  }, [])

  const sidebar = <AdminNavigation admin={admin} loggingOut={loggingOut} logout={logout} onNavigate={() => drawer.current?.close()} />
  return <div className="admin-console">
    <a className="skip-link" href="#admin-main">跳到主要内容</a>
    <aside className="admin-sidebar">{sidebar}</aside>
    <dialog ref={drawer} className="admin-drawer" aria-label="管理导航" onClick={event => { if (event.target === event.currentTarget) drawer.current?.close() }}>
      <div className="admin-drawer-panel">
        <button className="icon-button admin-drawer-close" aria-label="关闭导航" onClick={() => drawer.current?.close()}><X size={20} /></button>
        {sidebar}
      </div>
    </dialog>
    <div className="admin-console-body">
      <header className="admin-topbar">
        <button className="icon-button admin-menu-toggle" aria-label="打开管理导航" aria-haspopup="dialog" onClick={() => drawer.current?.showModal()}><List size={22} /></button>
        <nav className="admin-breadcrumb" aria-label="当前位置"><Link to="/admin">管理后台</Link><CaretRight size={13} aria-hidden="true" /><span aria-current="page">{title}</span></nav>
        <Link className="quiet-link" to="/">返回网站 <ArrowUpRight size={15} /></Link>
      </header>
      <main id="admin-main" className="admin-workspace">
        {error && <div className="admin-banner" role="alert">{error}</div>}
          {children ?? <Outlet/>}
      </main>
    </div>
  </div>
}

function AdminNavigation({ admin, loggingOut, logout, onNavigate }: Omit<LayoutProps, 'error'> & { onNavigate: () => void }) {
  return <div className="admin-navigation-content">
    <Link className="brand admin-sidebar-brand" to="/admin" onClick={onNavigate} aria-label="ImgHub 管理首页"><span className="brand-mark"><ImageSquare size={23} weight="duotone" /></span><span>Img<span className="brand-light">Hub</span></span></Link>
      <div className="admin-space-label"><span/> 管理空间 <b>ADMIN</b></div>
    <nav className="admin-navigation" aria-label="后台功能">
      <p className="admin-nav-label">工作空间</p>
        {navigation.map(({to, label, description, icon: Icon}) => <NavLink key={to} to={to} onClick={onNavigate}
                                                                           className={({isActive}) => 'admin-nav-item' + (isActive ? ' is-active' : '')}><Icon
            size={21} weight="duotone"
            aria-hidden="true"/><span><strong>{label}</strong><small>{description}</small></span><CaretRight size={13}
                                                                                                             className="admin-nav-arrow"
                                                                                                             aria-hidden="true"/></NavLink>)}
    </nav>
    <div className="admin-sidebar-bottom">
        <div className="admin-sidebar-signature" aria-hidden="true"><span>IMAGE HUB</span><p>让创作有序，让分享发生。</p>
        </div>
      <div className="admin-account">
          <span className="admin-account-avatar">{admin?.username.slice(0, 1).toUpperCase() || '·'}</span>
          <div><strong>{admin?.username || '正在加载…'}</strong><span>管理员</span></div>
          <button className="icon-button" aria-label="退出管理后台" title="退出管理后台" disabled={!admin || loggingOut}
                  onClick={logout}><SignOut size={19}/></button>
      </div>
    </div>
  </div>
}

/** 路由代码与身份校验共用后台外壳，不提前挂载需要权限的数据页。 */
export function AdminLoading() {
    return <AdminLayout admin={null} loggingOut={false} logout={() => {
    }} error="">
        <div className="admin-loading-content" role="status" aria-label="正在加载管理页面">
            <span className="skeleton-block skeleton-title" aria-hidden="true"/>
            {Array.from({length: 7}, (_, index) => <div className="skeleton-block" key={index} aria-hidden="true"/>)}
        </div>
    </AdminLayout>
}
