import { Suspense, useRef, useState } from 'react'
import gsap from 'gsap'
import { useGSAP } from '@gsap/react'
import { Link } from 'react-router-dom'
import { ArrowRight, ArrowUp, ArrowUpRight, Check, CheckCircle, CloudArrowUp, Copy, ImageSquare, LinkSimple, Pause, Play, Plus, X } from '@phosphor-icons/react'
import { fileAccept, Starfield } from '../App'
import type { AppState } from '../App'
import { formatSize, MAX_FILES } from '../lib/rules'

gsap.registerPlugin(useGSAP)

export default function Home({ app }: { app: AppState }) {
  const page = useRef<HTMLElement>(null)
  const intro = useRef<gsap.core.Timeline | null>(null)
  const paused = useRef(app.paused)
  const input = useRef<HTMLInputElement>(null)
  const lensTarget = useRef<HTMLDivElement>(null)
  const dragDepth = useRef(0)
  const [dragging, setDragging] = useState(false)
  const [focused, setFocused] = useState(false)
  const ready = app.pending.filter(item => item.status === 'ready' || item.status === 'error')
  const completed = app.pending.filter(item => item.status === 'done')
  const progress = app.pending.length ? Math.round(app.pending.reduce((total, item) => total + item.progress, 0) / app.pending.length) : 0

  useGSAP(() => {
    const media = gsap.matchMedia()
    media.add('(prefers-reduced-motion: no-preference)', () => {
      if (paused.current) return
      intro.current = gsap.timeline({ defaults: { ease: 'power3.out', duration: 0.85 } })
        .from('.hero-heading .eyebrow', { autoAlpha: 0, y: 10 }, 0.1)
        .from('.hero-heading h1', { autoAlpha: 0, y: 24, duration: 1.15 }, 0.22)
        .from('.hero-heading > p', { autoAlpha: 0, y: 12 }, 0.4)
        .from('.upload-shell', { autoAlpha: 0, duration: 1 }, 0.5)
        .from('.hero-bottom', { autoAlpha: 0, y: 8 }, 0.85)
        .from('.workflow-step, .step-arrow', { autoAlpha: 0, y: 10, stagger: 0.06 }, 1)
      return () => { intro.current = null }
    }, page)
    return () => media.revert()
  }, { scope: page })

  useGSAP(() => {
    paused.current = app.paused
    // Never leave the upload controls hidden when motion is paused mid-entrance.
    if (app.paused) intro.current?.progress(1).pause()
  }, { dependencies: [app.paused], scope: page })

  return <main id="main" className="home" ref={page}>
    <section className="hero" aria-labelledby="hero-title">
      <Suspense fallback={<div className="starfield" />}><Starfield target={lensTarget} active={dragging || focused || app.busy} paused={app.paused} /></Suspense>
      <div className="hero-content">
        <div className="hero-heading">
          <span className="eyebrow"><span className="tiny-line" />让分享，从一张图片开始<span className="tiny-line" /></span>
          <h1 id="hero-title">你的图片，<span>即刻分享。</span></h1>
          <p>拖入图片，获取链接。把值得分享的，送到任何地方。</p>
        </div>
        <div className={`upload-shell ${dragging ? 'is-dragging' : ''} ${app.busy ? 'is-uploading' : ''}`} ref={lensTarget} data-paused={app.paused}
          onFocusCapture={() => setFocused(true)} onBlurCapture={event => { if (!event.currentTarget.contains(event.relatedTarget)) setFocused(false) }}
          onDragEnter={event => { event.preventDefault(); dragDepth.current++; if (!app.busy) setDragging(true) }}
          onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = app.busy ? 'none' : 'copy' }}
          onDragLeave={event => { event.preventDefault(); dragDepth.current--; if (dragDepth.current <= 0) setDragging(false) }}
          onDrop={event => { event.preventDefault(); dragDepth.current = 0; setDragging(false); void app.selectFiles(event.dataTransfer.files) }}>
          <svg className="rim-filter" aria-hidden="true"><filter id="upload-rim-noise" x="-10%" y="-10%" width="120%" height="120%" colorInterpolationFilters="sRGB">
            <feTurbulence type="fractalNoise" baseFrequency="0.06 0.45" numOctaves="2" seed="12" result="noise" />
            <feDisplacementMap in="SourceGraphic" in2="noise" scale="1.8" xChannelSelector="R" yChannelSelector="G" result="ripple" />
            <feComposite in="ripple" in2="noise" operator="arithmetic" k1="1.3" k2="0.5" k3="0" k4="0" />
          </filter></svg>
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
            <span className="upload-limit"><ImageSquare size={16} />{app.pending.length ? `已选择 ${app.pending.length} / ${MAX_FILES} 张` : `支持批量上传，最多 ${MAX_FILES} 张`}</span>
            {app.user
              ? <button className="button button-primary button-upload" disabled={!ready.length || app.busy || app.selecting} onClick={app.upload}>
                  {app.busy ? `上传中 ${progress}%` : app.pending.some(item => item.status === 'error') ? '重试失败图片' : '开始上传'}<ArrowUp size={17} weight="bold" />
                </button>
              : <Link to="/login" state={{ from: '/' }} className="button button-primary button-upload">登录后开始上传 <ArrowUpRight size={17} /></Link>}
          </div>
          {app.busy && <div className="overall-progress" role="progressbar" aria-label="图片上传进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}><span style={{ transform: `scaleX(${progress / 100})` }} /></div>}
        </div>

        {app.pending.length > 0 && <div className="selection-panel">
          <div className="selection-heading"><span>已选图片 <span className="count-badge">{app.pending.length}</span></span>
            <button className="text-button" disabled={app.busy} onClick={app.clearPending}>清空列表</button>
          </div>
          <div className="selection-grid">
            {app.pending.map(item => <article className="selected-image" key={item.id}>
              <button className="selected-preview" aria-label={`预览 ${item.file.name}`} onClick={() => app.setPreview({ name: item.file.name, preview: item.preview, size: item.file.size, width: item.width, height: item.height })}>
                <img src={item.preview} alt={item.file.name} />
              </button>
              <button className="remove-image" aria-label={`移除 ${item.file.name}`} disabled={app.busy} onClick={() => app.removePending(item.id)}><X size={14} /></button>
              {item.status === 'done' && <span className="image-done" aria-label="上传完成"><Check size={13} weight="bold" /></span>}
              {item.status === 'uploading' && <span className="image-progress">{item.progress}%</span>}
              <span className="selected-name" title={item.file.name}>{item.file.name}</span>
              <span className="selected-size">{formatSize(item.file.size)}</span>
              {item.error && <span className="field-error" role="alert">{item.error}</span>}
            </article>)}
            {app.pending.length < MAX_FILES && <button className="add-image" aria-label="继续添加图片" disabled={app.busy || app.selecting} onClick={() => input.current?.click()}><Plus size={22} /><span>继续添加</span></button>}
          </div>
        </div>}

        {completed.length > 0 && <section className="upload-results">
          <div className="selection-heading"><span className="success-title"><CheckCircle weight="fill" size={18} />上传完成</span><Link className="quiet-link" to="/history">查看全部记录 <ArrowRight size={14} /></Link></div>
          {completed.map(item => {
            const record = item.record
            return record && <div className="result-row" key={item.id}>
              <img src={item.preview} alt="" /><div className="result-info"><strong>{record.name}</strong><input aria-label={`${record.name} 的原始链接`} value={record.url} readOnly onFocus={event => event.target.select()} /></div>
              <button className="icon-button" title="复制原始 URL" aria-label={`复制 ${record.name} 的原始 URL`} onClick={() => app.copyUrl(record.url)}><Copy size={18} /></button>
            </div>
          })}
        </section>}

        <div className="hero-bottom">
          <span><LinkSimple size={15} />一个链接，连接你的每次分享</span>
          <button className="motion-button" onClick={() => app.setPaused(!app.paused)} aria-pressed={app.paused} aria-label={app.paused ? '播放背景动效' : '暂停背景动效'}>
            {app.paused ? <Play size={13} weight="fill" /> : <Pause size={13} weight="fill" />}<span>{app.paused ? '播放动效' : '暂停动效'}</span>
          </button>
        </div>
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
