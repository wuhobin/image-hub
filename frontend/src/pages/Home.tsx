import { lazy, Suspense, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { CheckCircle } from '@phosphor-icons/react/dist/csr/CheckCircle'
import { CloudArrowUp } from '@phosphor-icons/react/dist/csr/CloudArrowUp'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { LinkSimple } from '@phosphor-icons/react/dist/csr/LinkSimple'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { X } from '@phosphor-icons/react/dist/csr/X'
import { Ambient } from '../components/Ambient'
import { QuotaStatus } from '../components/QuotaStatus'
import type { AppState } from '../App'
import { ACCEPTED_TYPES, formatSize, MAX_FILES } from '../lib/rules'

const UploadProgress = lazy(() => import('../components/UploadProgress'))
const fileAccept = ACCEPTED_TYPES.join(',')

export default function Home({ app }: { app: AppState }) {
  const input = useRef<HTMLInputElement>(null)
  const lensTarget = useRef<HTMLDivElement>(null)
  const dragDepth = useRef(0)
  const [dragging, setDragging] = useState(false)
  const ready = app.pending.filter(item => item.status === 'ready' || item.status === 'error')
  const completed = app.pending.filter(item => item.status === 'done')
  const progress = app.pending.length ? Math.floor(app.pending.reduce((total, item) => total + item.progress, 0) / app.pending.length) : 0

  return <main id="main" className="home">
    <section className="hero" aria-labelledby="hero-title">
      <Ambient target={lensTarget} />
      <div className="hero-content">
        <div className="hero-heading">
          <span className="eyebrow"><span className="tiny-line" />上传已有图片<span className="tiny-line" /></span>
          <h1 id="hero-title">你的图片，<span>即刻分享。</span></h1>
          <p>拖入图片，获取链接。把值得分享的，送到任何地方。</p>
        </div>
        <div className={`upload-shell ${dragging ? 'is-dragging' : ''} ${app.busy ? 'is-uploading' : ''}`} ref={lensTarget}
          onDragEnter={event => { event.preventDefault(); dragDepth.current++; if (!app.busy) setDragging(true) }}
          onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = app.busy ? 'none' : 'copy' }}
          onDragLeave={event => { event.preventDefault(); dragDepth.current--; if (dragDepth.current <= 0) setDragging(false) }}
          onDrop={event => { event.preventDefault(); dragDepth.current = 0; setDragging(false); void app.selectFiles(event.dataTransfer.files) }}>
          <span className="upload-rim" aria-hidden="true" />
          <input ref={input} className="visually-hidden" type="file" multiple accept={fileAccept} aria-label="选择要上传的图片" tabIndex={-1}
            onChange={event => { if (event.target.files) void app.selectFiles(event.target.files); event.target.value = '' }} disabled={app.busy || app.selecting} />
          <button className="drop-zone" onClick={() => input.current?.click()} disabled={app.busy || app.selecting || app.pending.length >= MAX_FILES} aria-label="选择图片或拖拽图片到此处">
            <span className="upload-symbol"><CloudArrowUp size={33} weight="light" /></span>
            <strong>{dragging ? '松开鼠标，让分享开始' : app.selecting ? '正在读取图片…' : app.busy ? '正在上传你的图片…' : '将图片拖到这里'}</strong>
            <span className="drop-description">或<span className="text-accent">点击选择文件</span></span>
            <span className="drop-formats">JPG · PNG · WEBP · GIF<span />单张最大 10 MB</span>
          </button>
          <div className="upload-toolbar">
            <div className="upload-info">
              <span className="upload-limit"><ImageSquare size={16} />{app.pending.length ? `已选择 ${app.pending.length} / ${MAX_FILES} 张` : `支持批量上传，最多 ${MAX_FILES} 张`}</span>
              <QuotaStatus app={app} />
            </div>
            {app.initializing
              ? <button className="button button-primary button-upload" disabled aria-label="正在恢复登录"><span className="quota-placeholder" aria-hidden="true" /><ArrowUp size={17} /></button>
              : app.user
              ? <button className="button button-primary button-upload" disabled={!ready.length || app.busy || app.selecting || !app.quota || !!app.quotaError || app.quota.remaining === 0} onClick={app.upload}>
                  {app.busy ? '上传中…' : app.quotaError ? '额度待更新' : app.quota?.remaining === 0 ? '额度已用完' : app.pending.some(item => item.status === 'error') ? '重试失败图片' : '开始上传'}<ArrowUp size={17} weight="bold" />
                </button>
              : <Link to="/login" state={{ from: '/upload' }} className="button button-primary button-upload">登录后开始上传 <ArrowUpRight size={17} /></Link>}
          </div>
        </div>

        {app.pending.length > 0 && <div className="selection-panel">
          <div className="selection-heading"><span>本次上传 <span className="count-badge">{app.pending.length} 张</span></span>
            <button className="text-button" disabled={app.busy} onClick={app.clearPending}>清空列表</button>
          </div>
          <div className="selection-list">
            {app.pending.map(item => <article className="selected-image" key={item.id} data-status={item.status}>
              <button className="selected-preview" aria-label={`预览 ${item.file.name}`} onClick={() => app.setPreview({ name: item.file.name, preview: item.preview, size: item.file.size, width: item.width, height: item.height })}>
                <img src={item.preview} alt={item.file.name} loading="lazy" decoding="async" width={76} height={76} />
              </button>
              <div className="selected-details">
                <div className="selected-file-heading">
                  <span className="selected-name" title={item.file.name}>{item.file.name}</span>
                  <button className="remove-image" aria-label={`移除 ${item.file.name}`} title="从列表移除" disabled={app.busy} onClick={() => app.removePending(item.id)}><X size={16} /></button>
                </div>
                <div className="selected-meta">
                  <span className="selected-size">{formatSize(item.file.size)}</span>
                  {item.status === 'done' ? <span className="image-done" aria-label="上传完成"><CheckCircle size={14} weight="fill" />已上传</span>
                    : <span className="file-status">{item.status === 'uploading' ? '正在上传' : item.status === 'error' ? '上传失败' : '等待上传'}</span>}
                </div>
                {item.error && <span className="field-error" role="alert">{item.error}</span>}
              </div>
              {item.record && <div className="result-row">
                <LinkSimple size={16} aria-hidden="true" />
                <input aria-label={`${item.record.name} 的原始链接`} value={item.record.url} readOnly onFocus={event => event.target.select()} />
                <button className="copy-link-button" title="复制原始 URL" aria-label={`复制 ${item.record.name} 的原始 URL`} onClick={() => app.copyUrl(item.record!.url)}><Copy size={15} /><span>复制链接</span></button>
              </div>}
            </article>)}
          </div>
          <div className="selection-footer">
            {app.pending.length < MAX_FILES && <button className="add-image" aria-label="继续添加图片" disabled={app.busy || app.selecting} onClick={() => input.current?.click()}><Plus size={16} /><span>继续添加</span></button>}
            {completed.length > 0 && <Link className="quiet-link" to="/history">查看我的图片 <ArrowUpRight size={14} /></Link>}
          </div>
          {(app.busy || completed.length === app.pending.length) && <Suspense fallback={<progress className="upload-progress" value={progress} max={100} aria-label="图片上传总进度" />}><UploadProgress value={progress}
            label={app.busy ? `正在上传 ${app.pending.findIndex(item => item.status === 'uploading') + 1}/${app.pending.length}` : `上传完成 ${completed.length}/${app.pending.length}`} /></Suspense>}
        </div>}

      </div>
    </section>
    <section className="workflow" aria-label="使用步骤">
      <div className="workflow-step"><span className="step-icon"><ImageSquare size={22} weight="light" /></span><div><h2>选好图片</h2><p>随手拖入，批量也轻松</p></div></div>
      <ArrowRight className="step-arrow" size={20} />
      <div className="workflow-step"><span className="step-icon"><CloudArrowUp size={23} weight="light" /></span><div><h2>一键上传</h2><p>上传过程，进度清晰可见</p></div></div>
      <ArrowRight className="step-arrow" size={20} />
      <div className="workflow-step"><span className="step-icon"><LinkSimple size={22} weight="light" /></span><div><h2>随处分享</h2><p>复制链接，嵌入你的世界</p></div></div>
    </section>
  </main>
}
