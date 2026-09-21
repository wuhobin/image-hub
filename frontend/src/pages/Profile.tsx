import { useEffect, useState } from 'react'
import { ArrowClockwise } from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { CaretLeft } from '@phosphor-icons/react/dist/csr/CaretLeft'
import { CaretRight } from '@phosphor-icons/react/dist/csr/CaretRight'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { Page, QuotaUsage } from '../lib/types'
import type { AppState } from '../App'

export default function Profile({ app }: { app: AppState }) {
  const [retrying, setRetrying] = useState(false)
  const [records, setRecords] = useState<Page<QuotaUsage> | null>(null)
  const [recordsLoading, setRecordsLoading] = useState(true)
  const [recordsError, setRecordsError] = useState('')
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!app.user || app.initializing) return
    const controller = new AbortController()
    setRecordsLoading(true)
    setRecordsError('')
    void api<Page<QuotaUsage>>('/quota/records?page=' + page + '&pageSize=10', { signal: controller.signal })
      .then(result => { if (!controller.signal.aborted) setRecords(result) })
      .catch(error => { if (!controller.signal.aborted) setRecordsError(error instanceof Error ? error.message : '消耗记录读取失败') })
      .finally(() => { if (!controller.signal.aborted) setRecordsLoading(false) })
    const refresh = () => setRevision(value => value + 1)
    window.addEventListener('focus', refresh)
    return () => { controller.abort(); window.removeEventListener('focus', refresh) }
  }, [app.user, app.initializing, page, revision])

  const { quota, quotaError } = app
  const loading = app.initializing || (!quota && !quotaError) || retrying
  const metrics = [
    { label: '剩余额度', value: quota?.remaining, note: '当前可用于上传与创作', tone: 'available' },
    { label: '总额度', value: quota?.total, note: '账户累计可用额度', tone: 'total' },
    { label: '已用额度', value: quota?.used, note: '上传与 AI 创作累计消耗', tone: 'used' },
    { label: '预留额度', value: quota?.reserved, note: '生成中占用，失败后释放', tone: 'reserved' },
  ]

  async function retry() {
    setRetrying(true)
    try { await app.refreshQuota() } catch { /* 读取失败由共享额度状态展示。 */ }
    finally { setRetrying(false) }
  }

  return <main id="main" className="history-page profile-page">
    <div className="profile-overview">
      <header className="profile-heading">
        <span className="profile-avatar" aria-hidden="true">{app.user?.slice(0, 1).toUpperCase() || '·'}</span>
        <div><h1 title={app.user || undefined}>{app.user || '个人中心'}</h1><p>个人中心 <span>·</span> 我的创作账户</p></div>
      </header>
      <section className="profile-quota" aria-labelledby="profile-quota-title" aria-busy={loading}>
        <h2 id="profile-quota-title" className="visually-hidden">共享额度</h2>
        <dl className="profile-metrics">
          {metrics.map(({ label, value, note, tone }) => <div className={'profile-metric is-' + tone} key={label}>
            <dt>{label}</dt>
            <dd>{loading ? <span className="skeleton-block profile-quota-skeleton" aria-label="正在加载" />
              : value == null ? '—' : <>{value}<span className="profile-quota-unit">次</span></>}
              <span className="profile-metric-note">{note}</span>
            </dd>
          </div>)}
        </dl>
        {(loading || quotaError || quota?.remaining === 0) && <div className="profile-quota-status" role="status">
          {loading ? '正在读取额度…' : quotaError ? <button type="button" className="text-button" onClick={retry}>{quotaError} · 重试</button>
            : '共享额度已用完。'}
        </div>}
      </section>
    </div>
    <div className="profile-dashboard">
    {app.user && <section className="profile-usage" aria-labelledby="profile-usage-title" aria-busy={recordsLoading}>
      <header>
        <div><h2 id="profile-usage-title">额度消耗记录</h2><p>仅记录成功消耗，失败任务不扣费。</p></div>
        <button type="button" className="profile-refresh" disabled={recordsLoading} onClick={() => setRevision(value => value + 1)}>
          <ArrowClockwise size={16} aria-hidden="true" />{recordsLoading ? '读取中' : '刷新记录'}
        </button>
      </header>
      {recordsError ? <div className="profile-usage-state" role="alert"><p>{recordsError}</p><button type="button" className="text-button" onClick={() => setRevision(value => value + 1)}>重新加载</button></div>
        : !records && recordsLoading ? <div className="profile-usage-state" role="status"><span className="profile-loading-dot" />正在读取消耗记录…</div>
        : !records?.records.length ? <div className="profile-usage-state">
          <span className="profile-empty-icon"><Sparkle size={24} aria-hidden="true" /></span>
          <strong>暂无额度消耗记录</strong><p>上传图片或完成一次 AI 创作后，记录会出现在这里。</p>
          <Link className="quiet-link" to="/">开始创作<ArrowUpRight size={14} aria-hidden="true" /></Link>
        </div>
        : <>
          <ul className="profile-usage-list" tabIndex={0} aria-label="当前账号的额度消耗明细">
            {records.records.map(record => <li key={record.id}>
              <div className="quota-usage-description">
                <span className="quota-usage-scene">{record.scene === 'AI_GENERATION' ? 'AI 创作' : record.scene === 'IMAGE_UPLOAD' ? '图片上传' : record.scene}</span>
                <span className="quota-usage-name" title={record.description + ' · ' + record.bizId}>{record.description}</span>
                <small className="visually-hidden">记录编号 {record.bizId}</small>
              </div>
              <div className="quota-usage-detail">
                <span className="quota-usage-amount">−{record.amount}<span> 次</span></span>
                <time dateTime={record.createTime?.replace(' ', 'T')} title={record.createTime}>{record.createTime?.slice(5, 16).replaceAll('-', '/') || '—'}</time>
              </div>
            </li>)}
          </ul>
          <div className="profile-usage-pagination">
            <span>共 {records.total} 条记录</span>
            <div>
              <button type="button" aria-label="上一页" disabled={recordsLoading || page <= 1} onClick={() => setPage(value => value - 1)}><CaretLeft size={14} aria-hidden="true" /></button>
              <span>{records.current} / {Math.max(1, records.pages)}</span>
              <button type="button" aria-label="下一页" disabled={recordsLoading || page >= records.pages} onClick={() => setPage(value => value + 1)}><CaretRight size={14} aria-hidden="true" /></button>
            </div>
          </div>
        </>}
    </section>}
      <aside className="profile-sidebar" aria-label="创作与额度说明">
        <section className="profile-panel">
          <header><h2>创作空间</h2><span>从这里开始</span></header>
          <nav className="profile-shortcuts" aria-label="创作快捷入口">
            <Link to="/"><span className="profile-shortcut-icon"><Sparkle size={21} aria-hidden="true" /></span><div><strong>AI 创作</strong><span>把想法变成一张图片</span></div><ArrowUpRight size={17} aria-hidden="true" /></Link>
            <Link to="/upload"><span className="profile-shortcut-icon is-upload"><ImageSquare size={21} aria-hidden="true" /></span><div><strong>上传图片</strong><span>保存图片，随时分享</span></div><ArrowUpRight size={17} aria-hidden="true" /></Link>
          </nav>
          <Link to="/history" className="profile-library-link">查看我的图片<ArrowUpRight size={15} aria-hidden="true" /></Link>
        </section>
        <section className="profile-panel profile-rules">
          <header><h2>额度说明</h2><span>上传与创作共用</span></header>
          <dl>
            <div><dt>成功后扣除</dt><dd>每成功上传或生成一张图片，消耗 1 次额度。</dd></div>
            <div><dt>生成时预留</dt><dd>剩余额度已扣除生成中的预留；任务失败后自动释放。</dd></div>
            <div><dt>累计使用</dt><dd>额度不按天重置，删除图片不会返还已用额度。</dd></div>
          </dl>
        </section>
      </aside>
    </div>
  </main>
}
