import { useEffect, useState } from 'react'
import { ArrowClockwise } from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { CaretLeft } from '@phosphor-icons/react/dist/csr/CaretLeft'
import { CaretRight } from '@phosphor-icons/react/dist/csr/CaretRight'
import {Gift} from '@phosphor-icons/react/dist/csr/Gift'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { Link } from 'react-router-dom'
import { api } from '../lib/api'
import type { Page, QuotaUsage } from '../lib/types'
import type { AppState } from '../App'
import {CheckInCard} from '../components/CheckInCard'
import {Avatar} from '../components/Avatar'
import {AvatarEditor} from '../components/AvatarEditor'
import {Camera} from '@phosphor-icons/react/dist/csr/Camera'

export default function Profile({ app }: { app: AppState }) {
  const [retrying, setRetrying] = useState(false)
    const [editingAvatar, setEditingAvatar] = useState(false)
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
        .catch(error => {
            if (!controller.signal.aborted) setRecordsError(error instanceof Error ? error.message : '积分明细读取失败')
        })
      .finally(() => { if (!controller.signal.aborted) setRecordsLoading(false) })
    const refresh = () => setRevision(value => value + 1)
    window.addEventListener('focus', refresh)
    return () => { controller.abort(); window.removeEventListener('focus', refresh) }
  }, [app.user, app.initializing, page, revision])

  const { quota, quotaError } = app
  const loading = app.initializing || (!quota && !quotaError) || retrying
  const metrics = [
    { label: '剩余积分', value: quota?.remaining, note: '当前可用于上传与创作', tone: 'available' },
    { label: '总积分', value: quota?.total, note: '账户累计可用积分', tone: 'total' },
    { label: '已用积分', value: quota?.used, note: '上传与 AI 创作累计消耗', tone: 'used' },
    { label: '预留积分', value: quota?.reserved, note: '生成中占用，失败后释放', tone: 'reserved' },
  ]

  async function retry() {
    setRetrying(true)
    try { await app.refreshQuota() } catch { /* 读取失败由共享积分状态展示。 */ }
    finally { setRetrying(false) }
  }

  return <main id="main" className="history-page profile-page">
    <div className="profile-overview">
      <header className="profile-heading">
          <button type="button" className="profile-avatar-edit" disabled={app.initializing || !app.user}
                  aria-label="更换头像" aria-haspopup="dialog" onClick={() => setEditingAvatar(true)}>
              <Avatar name={app.user} url={app.avatarUrl} className="profile-avatar"/>
              <span className="profile-avatar-camera"><Camera size={14} aria-hidden="true"/></span>
          </button>
        <div><h1 title={app.user || undefined}>{app.user || '个人中心'}</h1><p>个人中心 <span>·</span> 我的创作账户</p></div>
      </header>
      <section className="profile-quota" aria-labelledby="profile-quota-title" aria-busy={loading}>
        <h2 id="profile-quota-title" className="visually-hidden">共享积分</h2>
        <dl className="profile-metrics">
          {metrics.map(({ label, value, note, tone }) => <div className={'profile-metric is-' + tone} key={label}>
            <dt>{label}</dt>
            <dd>{loading ? <span className="skeleton-block profile-quota-skeleton" aria-label="正在加载" />
              : value == null ? '—' : <>{value}<span className="profile-quota-unit">积分</span></>}
              <span className="profile-metric-note">{note}</span>
            </dd>
          </div>)}
        </dl>
          <div className="profile-quota-status" role="status">
          {loading ? '正在读取积分…' : quotaError ? <button type="button" className="text-button" onClick={retry}>{quotaError} · 重试</button>
              : quota?.remaining === 0 ? '共享积分已用完。' : '\u00a0'}
          </div>
      </section>
    </div>
      {(app.initializing || app.user) && <CheckInCard enabled={!!app.user} key={app.user} onClaimed={() => {
          setPage(1)
          setRevision(value => value + 1)
          void app.refreshQuota().catch(() => {
          })
      }}/>}
    <div className="profile-dashboard">
        {(app.initializing || app.user) &&
            <section className="profile-usage" aria-labelledby="profile-usage-title" aria-busy={recordsLoading}>
      <header>
          <div><h2 id="profile-usage-title">积分明细</h2><p>签到奖励与创作消费，每一笔都有记录。</p></div>
        <button type="button" className="profile-refresh" disabled={recordsLoading} onClick={() => setRevision(value => value + 1)}>
          <ArrowClockwise size={16} aria-hidden="true" />{recordsLoading ? '读取中' : '刷新记录'}
        </button>
      </header>
      {recordsError ? <div className="profile-usage-state" role="alert"><p>{recordsError}</p><button type="button" className="text-button" onClick={() => setRevision(value => value + 1)}>重新加载</button></div>
          : !records && recordsLoading ?
              <div className="profile-usage-state" role="status"><span className="profile-loading-dot"/>正在读取积分明细…
              </div>
        : !records?.records.length ? <div className="profile-usage-state">
          <span className="profile-empty-icon"><Sparkle size={24} aria-hidden="true" /></span>
                      <strong>暂无积分明细</strong><p>签到领取奖励、上传图片或完成创作后，记录会出现在这里。</p>
          <Link className="quiet-link" to="/">开始创作<ArrowUpRight size={14} aria-hidden="true" /></Link>
        </div>
        : <>
                      <ul className="profile-usage-list" tabIndex={0} aria-label="当前账号的积分收支明细">
            {records.records.map(record => <li key={record.id}>
                <div className="quota-usage-entry">
                <span className={'quota-usage-icon' + (record.direction === 'INCOME' ? ' is-income' : '')}
                      aria-hidden="true">
                  {record.direction === 'INCOME' ? <Gift size={18}/> : record.scene === 'IMAGE_UPLOAD' ?
                      <ImageSquare size={18}/> : <Sparkle size={18}/>}
                </span>
                    <div className="quota-usage-description">
                        <span className="quota-usage-scene">{{
                            AI_GENERATION: 'AI 创作',
                            IMAGE_UPLOAD: '图片上传',
                            DAILY_CHECK_IN: '每日签到',
                            CHECK_IN_BONUS: '连续签到奖励'
                        }[record.scene] || record.scene}</span>
                        <span className="quota-usage-name"
                              title={record.description + ' · ' + record.bizId}>{record.description}</span>
                        <small className="visually-hidden">记录编号 {record.bizId}</small>
                    </div>
              </div>
              <div className="quota-usage-detail">
                  <span
                      className={'quota-usage-amount' + (record.direction === 'INCOME' ? ' is-income' : '')}>{record.direction === 'INCOME' ? '+' : '−'}{record.amount}<span> 积分</span></span>
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
      <aside className="profile-sidebar" aria-label="创作与积分说明">
        <section className="profile-panel">
          <header><h2>创作空间</h2><span>从这里开始</span></header>
          <nav className="profile-shortcuts" aria-label="创作快捷入口">
            <Link to="/"><span className="profile-shortcut-icon"><Sparkle size={21} aria-hidden="true" /></span><div><strong>AI 创作</strong><span>把想法变成一张图片</span></div><ArrowUpRight size={17} aria-hidden="true" /></Link>
            <Link to="/upload"><span className="profile-shortcut-icon is-upload"><ImageSquare size={21} aria-hidden="true" /></span><div><strong>上传图片</strong><span>保存图片，随时分享</span></div><ArrowUpRight size={17} aria-hidden="true" /></Link>
          </nav>
          <Link to="/history" className="profile-library-link">查看我的图片<ArrowUpRight size={15} aria-hidden="true" /></Link>
        </section>
        <section className="profile-panel profile-rules">
          <header><h2>积分说明</h2><span>上传与创作共用</span></header>
          <dl>
            <div><dt>成功后扣除</dt><dd>上传每张消耗 1 积分，AI 创作按所选模型扣除。</dd></div>
            <div><dt>生成时预留</dt><dd>剩余积分已扣除生成中的预留；任务失败后自动释放。</dd></div>
            <div><dt>累计使用</dt><dd>积分不按天重置，删除图片不会返还已用积分。</dd></div>
              <div>
                  <dt>签到奖励</dt>
                  <dd>每天主动签到领取积分，每连续 7 天还有额外奖励。奖励永久有效，不支持补签。</dd>
              </div>
          </dl>
        </section>
      </aside>
    </div>
      {editingAvatar && app.user && <AvatarEditor key={app.user} app={app} onClose={() => setEditingAvatar(false)}/>}
  </main>
}
