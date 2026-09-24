import {useEffect, useState} from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown } from '@phosphor-icons/react/dist/csr/ArrowDown'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { MagnifyingGlass } from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import { Trash } from '@phosphor-icons/react/dist/csr/Trash'
import { Warning } from '@phosphor-icons/react/dist/csr/Warning'
import { X } from '@phosphor-icons/react/dist/csr/X'
import type { AppState } from '../App'
import { Modal } from '../components/Modal'
import HistoryLoading, {HistoryHeading} from '../components/HistoryLoading'
import HistoryViewToggle, { getHistoryView } from '../components/HistoryViewToggle'
import { formatSize, toDateTime } from '../lib/rules'
import type { ImageRecord, ImageList } from '../lib/types'
import { api } from '../lib/api'

const timeFormat = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })

export default function History({ app }: { app: AppState }) {
  const [search, setSearch] = useState('')
  const [format, setFormat] = useState('全部格式')
  const [sourceType, setSourceType] = useState('')
  const [sort, setSort] = useState<'desc' | 'asc'>('desc')
  const [view, setView] = useState(getHistoryView)
  const [deleting, setDeleting] = useState<ImageRecord | null>(null)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)
  const [images, setImages] = useState<ImageList | null>(null)
  const data = images?.page
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [deletingBusy, setDeletingBusy] = useState(false)
  const [reload, setReload] = useState(0)
  useEffect(() => {
    const timer = setTimeout(() => { setQuery(search.trim()); setPage(1) }, 250)
    return () => clearTimeout(timer)
  }, [search])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    const params = new URLSearchParams({ search: query, type: format === '全部格式' ? '' : format, sourceType, sort, page: String(page), pageSize: '24' })
    api<ImageList>('/images?' + params, { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return
      if (!result.page.records.length && page > 1 && result.page.total <= (page - 1) * result.page.size) {
        setPage(Math.max(1, Math.ceil(result.page.total / result.page.size)))
        return
      }
      setImages(result)
    }).catch(error => {
      if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '记录读取失败')
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [query, format, sourceType, sort, page, app.revision, reload])
  const filtered = data?.records || []
  const count = data?.total || 0
  const bytes = images?.totalBytes || 0
  const hasFilters = !!query || format !== '全部格式' || !!sourceType

  async function confirmDelete() {
    if (!deleting || deletingBusy) return
    setDeletingBusy(true)
    try {
      await app.deleteRecord(deleting.id)
      setDeleting(null)
    } catch (error) {
      app.notify(error instanceof Error ? error.message : '删除失败，请重试', true)
    } finally { setDeletingBusy(false) }
  }

  if (loading && !data) return <HistoryLoading view={view} />

    return <main id="main" className="history-page library-ready" aria-busy={loading}>
    <HistoryHeading />
    <div className="library-summary">
      <div><strong>{count.toString().padStart(2, '0')}</strong><span>张图片</span></div>
      <span className="summary-divider" />
      <div><strong>{formatSize(bytes)}</strong><span>已上传大小</span></div>
      <span className="sample-note">你的上传与 AI 创作</span>
    </div>
    <div className="library-toolbar">
      <label className="search-field"><MagnifyingGlass size={18} /><input value={search} onChange={event => setSearch(event.target.value)} placeholder="搜索图片名称" aria-label="搜索图片名称" />{search && <button className="icon-button" aria-label="清空搜索" onClick={() => setSearch('')}><X size={15} /></button>}</label>
      <div className="filter-group">
        <select className="format-select" aria-label="按图片来源筛选" value={sourceType} onChange={event => { setSourceType(event.target.value); setPage(1) }}>
          <option value="">全部来源</option><option value="UPLOAD">用户上传</option><option value="AI">AI 创作</option>
        </select>
        <select className="format-select" aria-label="按图片格式筛选" value={format} onChange={event => { setFormat(event.target.value); setPage(1) }}>
          {['全部格式', 'JPG', 'PNG', 'WEBP', 'GIF'].map(value => <option key={value}>{value}</option>)}
        </select>
        <button type="button" className="sort-label sort-button" title={sort === 'desc' ? '点击切换为最早上传' : '点击切换为最新上传'}
          onClick={() => { setSort(current => current === 'desc' ? 'asc' : 'desc'); setPage(1) }}>
          {sort === 'desc' ? <ArrowDown size={15} aria-hidden="true" /> : <ArrowUp size={15} aria-hidden="true" />}{sort === 'desc' ? '最新上传' : '最早上传'}
        </button>
        <HistoryViewToggle view={view} onChange={setView} />
      </div>
    </div>
        {error ? <div className="empty-state" role="alert"><p>{error}</p>
            <button className="button button-secondary" onClick={() => setReload(value => value + 1)}>重新加载</button>
        </div> : filtered.length > 0 ? <div className={`image-library ${view === 'list' ? 'is-list' : ''}`}>
            {filtered.map((record, index) => <article className="library-card" key={record.id}
                                                      style={{animationDelay: Math.min(index, 7) * 40 + 180 + 'ms'}}>
        <button className="library-preview" onClick={() => app.setPreview(record)} aria-label={`预览 ${record.name}`}>
          <img src={record.preview} alt={record.name} loading={index > 3 ? 'lazy' : 'eager'} />
          <span className="preview-hint">查看图片 <ArrowUpRight size={16} /></span>
        </button>
        <div className="library-card-body">
          <div className="image-title"><h2 title={record.name}>{record.name}</h2>{record.sourceType === 'AI' && <span className="source-label">AI</span>}<span className="format-label">{record.type}</span></div>
          <div className="image-details"><span>{record.width} × {record.height}</span><span>{formatSize(record.size)}</span></div>
          <div className="image-card-bottom"><time dateTime={toDateTime(record.createdAt)}>{timeFormat.format(new Date(toDateTime(record.createdAt)))}</time>
            <div className="image-actions">
              <button className="icon-button copy-action" aria-label={`复制 ${record.name} 的原始 URL`} title="复制原始 URL" onClick={() => app.copyUrl(record.url)}><Copy size={17} /></button>
              <button className="icon-button delete-action" aria-label={`删除 ${record.name}`} title="删除图片" onClick={() => setDeleting(record)}><Trash size={17} /></button>
            </div>
          </div>
        </div>
      </article>)}
    </div> : <div className="empty-state"><span className="empty-icon"><ImageSquare size={36} weight="light" /></span>
      <h2>{hasFilters || count ? '没有找到这张图片' : '给这里添一点精彩'}</h2>
      <p>{hasFilters || count ? '换个关键词、格式或来源，再试一次。' : '生成第一张 AI 作品，或上传你已有的图片。'}</p>
      {hasFilters || count ? <button className="button button-secondary" onClick={() => { setSearch(''); setFormat('全部格式'); setSourceType('') }}>重置筛选</button> : <div className="creation-result-actions"><Link className="button button-primary" to="/">开始创作 <ArrowUpRight size={17} /></Link><Link className="button button-secondary" to="/upload">上传图片</Link></div>}
    </div>}
        {!error && data && data.total > 0 && <div className="library-pagination">
            <button className="button button-secondary" disabled={loading || page <= 1}
                    onClick={() => setPage(value => value - 1)}>上一页
            </button>
      <span>第 {page} / {data.pages} 页 · 共 {data.total} 张</span>
            <button className="button button-secondary" disabled={loading || page * data.size >= data.total}
                    onClick={() => setPage(value => value + 1)}>下一页
            </button>
    </div>}
    {deleting && <Modal title="删除这张图片？" onClose={() => { if (!deletingBusy) setDeleting(null) }} className="confirm-modal">
      <div className="delete-preview"><img src={deleting.preview} alt="" /><div><strong>{deleting.name}</strong><span>{formatSize(deleting.size)}</span></div></div>
      <p className="delete-warning"><Warning size={19} />图片文件和上传记录将同时删除，已有外链将失效。此操作无法撤销。</p>
      <div className="modal-actions"><button className="button button-secondary" disabled={deletingBusy} onClick={() => setDeleting(null)}>保留图片</button><button className="button button-danger" disabled={deletingBusy} onClick={confirmDelete}>{deletingBusy ? '删除中…' : '确认删除'}</button></div>
    </Modal>}
  </main>
}
