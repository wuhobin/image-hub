import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { MagnifyingGlass } from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import { ArrowClockwise } from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import { CaretLeft } from '@phosphor-icons/react/dist/csr/CaretLeft'
import { CaretRight } from '@phosphor-icons/react/dist/csr/CaretRight'
import { adminApi } from '../../lib/admin/api'
import type { UserPage } from '../../lib/admin/api'

const message = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试'

export default function AdminUsers() {
  const [params, setParams] = useSearchParams()
  const search = params.get('search') || ''
  const requestedPage = Number(params.get('page') || 1)
  const page = Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1
  const requestedSize = Number(params.get('pageSize') || 20)
  const pageSize = [20, 50, 100].includes(requestedSize) ? requestedSize : 20
  const [keyword, setKeyword] = useState(search)
  const [data, setData] = useState<UserPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [refresh, setRefresh] = useState(0)
  useEffect(() => { setKeyword(search) }, [search])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    const query = new URLSearchParams({ search, page: String(page), pageSize: String(pageSize) })
    void adminApi<UserPage>('/users?' + query, { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setData(result) })
      .catch(error => { if (!controller.signal.aborted) { setError(message(error)); setData(null) } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [search, page, pageSize, refresh])
  const update = (nextPage: number, nextSearch = search, nextSize = pageSize) => setParams({ search: nextSearch, page: String(nextPage), pageSize: String(nextSize) })
  const totalPages = data?.pages || 1
  return <div className="admin-users-page">
    <div className="admin-page-title"><div><span className="admin-eyebrow">账户与使用情况</span><h1>用户管理</h1><p>查找平台用户，查看他们的剩余积分。</p></div></div>
    <section className="admin-directory" aria-labelledby="admin-users-title">
      <div className="admin-directory-heading"><h2 id="admin-users-title">用户列表 <span>{!loading && data ? data.total.toLocaleString() : '—'}</span></h2><span className="admin-readonly">只读视图</span></div>
      <div className="admin-toolbar">
        <form className="admin-search" role="search" onSubmit={event => { event.preventDefault(); update(1, keyword.trim()); setRefresh(value => value + 1) }}>
          <MagnifyingGlass size={19} aria-hidden="true" /><label htmlFor="admin-search" className="visually-hidden">搜索用户名或邮箱</label><input id="admin-search" value={keyword} onChange={event => setKeyword(event.target.value)} maxLength={254} placeholder="搜索用户名或邮箱" type="search" /><button className="button button-secondary button-small" type="submit">搜索</button>
        </form>
        <button className="button button-small admin-refresh" onClick={() => setRefresh(value => value + 1)} disabled={loading}><ArrowClockwise size={17} />刷新列表</button>
      </div>
      <div className="admin-table-scroll" aria-busy={loading} tabIndex={0} aria-label="用户列表">
        <table className="admin-table"><thead><tr><th scope="col">用户</th><th scope="col">邮箱</th><th scope="col">注册时间</th><th scope="col" className="admin-number">剩余积分</th></tr></thead>
          <tbody>{loading ? Array.from({ length: 5 }, (_, index) => <tr key={index} className="admin-skeleton" aria-hidden="true"><td><span /></td><td><span /></td><td><span /></td><td><span /></td></tr>)
            : error ? <tr><td colSpan={4}><div className="admin-table-state"><p role="alert">{error}</p><button className="button button-secondary" onClick={() => setRefresh(value => value + 1)}>重新加载</button></div></td></tr>
            : !data?.records.length ? <tr><td colSpan={4}><div className="admin-table-state"><MagnifyingGlass size={30} /><h3>{search ? '没有找到匹配的用户' : page > 1 ? '这一页暂无用户' : '还没有用户加入'}</h3><p>{search ? '试试其他用户名或邮箱，或清除搜索条件。' : page > 1 ? '返回第一页查看用户列表。' : '用户注册后，会出现在这里。'}</p>{(search || page > 1) && <button className="button button-secondary" onClick={() => update(1, '')}>查看全部用户</button>}</div></td></tr>
            : data.records.map(user => <tr key={user.id}><td><div className="admin-user-cell"><span className="admin-user-avatar" aria-hidden="true">{user.username.slice(0, 1).toUpperCase()}</span><div><strong>{user.username}</strong><span>ID · {user.id}</span></div></div></td><td className="admin-email">{user.email}</td><td className="admin-date">{user.createTime || '—'}</td><td className="admin-number"><span className={user.remaining === 0 ? 'admin-quota admin-quota-empty' : 'admin-quota'} title={user.remaining == null ? '暂无积分记录' : undefined}>{user.remaining ?? '—'}</span>{user.remaining != null && <span className="admin-unit">积分</span>}</td></tr>)}</tbody>
        </table>
      </div>
      <div className="admin-pagination"><span role="status" aria-live="polite">{loading ? '正在加载用户…' : data ? '共 ' + data.total + ' 位用户' : '暂未加载用户'}</span>
        <div><label htmlFor="admin-page-size" className="visually-hidden">每页条数</label><select id="admin-page-size" value={pageSize} onChange={event => update(1, search, Number(event.target.value))}>{[20, 50, 100].map(size => <option key={size} value={size}>{size} 条 / 页</option>)}</select>
          <button className="icon-button" aria-label="上一页" disabled={loading || page <= 1} onClick={() => update(page - 1)}><CaretLeft size={17} /></button><span>{page} / {totalPages}</span><button className="icon-button" aria-label="下一页" disabled={loading || !!error || page >= totalPages} onClick={() => update(page + 1)}><CaretRight size={17} /></button></div></div>
    </section>
    <p className="admin-scroll-hint">左右滑动表格，查看完整用户信息。</p>
    <p className="admin-directory-note">剩余积分以本次查询为准。显示「—」表示暂无积分记录。</p>
  </div>
}
