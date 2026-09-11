import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUpRight, Copy, ImageSquare, MagnifyingGlass, Plus, Trash, Warning, X } from '@phosphor-icons/react'
import type { AppState } from '../App'
import { Modal } from '../components/Modal'
import { formatSize } from '../lib/rules'
import type { ImageRecord } from '../lib/mock'

const timeFormat = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })

export default function History({ app }: { app: AppState }) {
  const [search, setSearch] = useState('')
  const [format, setFormat] = useState('全部格式')
  const [deleting, setDeleting] = useState<ImageRecord | null>(null)
  const filtered = app.records.filter(item => item.name.toLowerCase().includes(search.toLowerCase()) && (format === '全部格式' || item.type === format))
  const bytes = app.records.reduce((sum, item) => sum + item.size, 0)

  return <main id="main" className="history-page page-enter">
    <div className="page-heading">
      <div><span className="section-kicker">属于你的图片空间</span><h1>每次分享，都在这里。</h1><p>查看、复制和管理你上传的每一张图片。</p></div>
      <Link className="button button-primary" to="/"><Plus size={17} />上传图片</Link>
    </div>
    <div className="library-summary">
      <div><strong>{app.records.length.toString().padStart(2, '0')}</strong><span>张图片</span></div>
      <span className="summary-divider" />
      <div><strong>{formatSize(bytes)}</strong><span>已上传大小</span></div>
      <span className="sample-note">包含示例图片</span>
    </div>
    <div className="library-toolbar">
      <label className="search-field"><MagnifyingGlass size={18} /><input value={search} onChange={event => setSearch(event.target.value)} placeholder="搜索图片名称" aria-label="搜索图片名称" />{search && <button className="icon-button" aria-label="清空搜索" onClick={() => setSearch('')}><X size={15} /></button>}</label>
      <div className="filter-group">
        <select aria-label="按图片格式筛选" value={format} onChange={event => setFormat(event.target.value)}>
          {['全部格式', 'JPG', 'PNG', 'WEBP', 'GIF'].map(value => <option key={value}>{value}</option>)}
        </select>
        <span className="sort-label"><ArrowDown size={15} />最新上传</span>
      </div>
    </div>
    {filtered.length > 0 ? <div className="image-library">
      {filtered.map((record, index) => <article className="library-card" key={record.id} style={{ '--card-index': Math.min(index, 7) } as React.CSSProperties}>
        <button className="library-preview" onClick={() => app.setPreview(record)} aria-label={`预览 ${record.name}`}>
          <img src={record.preview} alt={record.name} loading={index > 3 ? 'lazy' : 'eager'} />
          <span className="preview-hint">查看图片 <ArrowUpRight size={16} /></span>
        </button>
        <div className="library-card-body">
          <div className="image-title"><h2 title={record.name}>{record.name}</h2><span className="format-label">{record.type}</span></div>
          <div className="image-details"><span>{record.width} × {record.height}</span><span>{formatSize(record.size)}</span></div>
          <div className="image-card-bottom"><time dateTime={record.createdAt}>{timeFormat.format(new Date(record.createdAt))}</time>
            <div className="image-actions">
              <button className="icon-button copy-action" aria-label={`复制 ${record.name} 的原始 URL`} title="复制原始 URL" onClick={() => app.copyUrl(record.url)}><Copy size={17} /></button>
              <button className="icon-button delete-action" aria-label={`删除 ${record.name}`} title="删除图片" onClick={() => setDeleting(record)}><Trash size={17} /></button>
            </div>
          </div>
        </div>
      </article>)}
    </div> : <div className="empty-state"><span className="empty-icon"><ImageSquare size={36} weight="light" /></span>
      <h2>{app.records.length ? '没有找到这张图片' : '给这里添一点精彩'}</h2>
      <p>{app.records.length ? '换个关键词或图片格式，再试一次。' : '上传第一张图片，让分享从这里开始。'}</p>
      {app.records.length ? <button className="button button-secondary" onClick={() => { setSearch(''); setFormat('全部格式') }}>重置筛选</button> : <Link className="button button-primary" to="/">上传图片 <ArrowUpRight size={17} /></Link>}
    </div>}
    {filtered.length > 0 && <div className="library-end">已显示全部 {filtered.length} 张图片</div>}
    {deleting && <Modal title="删除这张图片？" onClose={() => setDeleting(null)} className="confirm-modal">
      <div className="delete-preview"><img src={deleting.preview} alt="" /><div><strong>{deleting.name}</strong><span>{formatSize(deleting.size)}</span></div></div>
      <p className="delete-warning"><Warning size={19} />图片文件和上传记录将同时删除，已有外链将失效。此操作无法撤销。</p>
      <div className="modal-actions"><button className="button button-secondary" onClick={() => setDeleting(null)}>保留图片</button><button className="button button-danger" onClick={() => { app.deleteRecord(deleting.id); setDeleting(null) }}>确认删除</button></div>
    </Modal>}
  </main>
}

