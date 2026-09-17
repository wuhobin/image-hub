import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import { DownloadSimple } from '@phosphor-icons/react/dist/csr/DownloadSimple'
import { ArrowUUpLeft } from '@phosphor-icons/react/dist/csr/ArrowUUpLeft'
import type { AppState } from '../App'
import type { AiModel, Generation, Page } from '../lib/types'
import { api, ApiError } from '../lib/api'
import { generationResolution, generationSizeForRatio, imageAspectRatio } from '../lib/rules'
import { Modal } from '../components/Modal'
import { Ambient } from '../components/Ambient'
import { QuotaStatus } from '../components/QuotaStatus'
import { CreationRatioPicker } from '../components/CreationRatioPicker'
import './create.css'

const labels: Record<Generation['status'], string> = {
  QUEUED: '等待生成', GENERATING: '正在生成', SAVING: '正在保存',
  SAVE_FAILED: '保存失败', SUCCEEDED: '已完成', FAILED: '生成失败', EXPIRED: '结果已过期', ABANDONED: '已放弃',
}
const runningStatuses: Generation['status'][] = ['QUEUED', 'GENERATING', 'SAVING']

/** 独立请求可能乱序返回；历史行不能从终态或保存阶段退回较早阶段。 */
function updateHistoryTask(record: Generation, observed: Generation): Generation {
  if (record.id !== observed.id) return record
  const previousStep = runningStatuses.indexOf(record.status)
  const observedStep = runningStatuses.indexOf(observed.status)
  return observedStep >= 0 && (previousStep < 0 || previousStep > observedStep) ? record : observed
}

const qualityNames: Record<string, string> = { low: '标准', medium: '精细', high: '高质量', auto: '自动' }
const message = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试'
type Submission = { requestId: string; prompt: string; modelId: number; size: string; quality: string }

/** AI 创作首页，游客可先写描述；登录后恢复任务，轮询不重放生成请求。 */
export default function Create({ app }: { app: AppState }) {
  const location = useLocation()
  const navigate = useNavigate()
  const workspace = useRef<HTMLDivElement>(null)
  const [models, setModels] = useState<AiModel[]>([])
  const [modelId, setModelId] = useState('')
  const [prompt, setPrompt] = useState(() => typeof location.state?.creationPrompt === 'string' ? location.state.creationPrompt.slice(0, 4000) : '')
  const [size, setSize] = useState('')
  const [quality, setQuality] = useState('')
  const [sizeNotice, setSizeNotice] = useState('')
  const [active, setActive] = useState<Generation | null>(null)
  const [latestTask, setLatestTask] = useState<Generation | null>(null)
  const [detail, setDetail] = useState<Generation | null>(null)
  const [history, setHistory] = useState<Page<Generation> | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(!!app.user)
  const [historyLoading, setHistoryLoading] = useState(!!app.user)
  const [error, setError] = useState('')
  const [modelsError, setModelsError] = useState('')
  const [modelsLoading, setModelsLoading] = useState(!!app.user)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [revision, setRevision] = useState(0)
  const [historyRevision, setHistoryRevision] = useState(0)
  const [modelsRevision, setModelsRevision] = useState(0)
  const [historyError, setHistoryError] = useState('')
  const observedTask = useRef<Generation | null>(null)
  const pending = useRef<Submission | null>(null)
  const submitting = useRef(false)
  const mounted = useRef(false)
  const appRef = useRef(app)
  appRef.current = app
  const model = models.find(item => String(item.id) === modelId)
  // 提交成功后立即出现在第一页；历史请求较慢时也保留当前任务卡片。
  const currentTask = active || latestTask
  const historyRecords = history?.records || []
  const visibleRecords = page === 1 && currentTask && !historyRecords.some(item => item.id === currentTask.id)
    ? [currentTask, ...historyRecords].slice(0, 12) : historyRecords
  const ratio = imageAspectRatio(size)
  const ratios = [...new Set(model?.sizes.map(imageAspectRatio) || [])]
  const ratioSizes = model?.sizes.filter(value => imageAspectRatio(value) === ratio) || []

  function changeRatio(nextRatio: string) {
    const nextSize = generationSizeForRatio(model?.sizes || [], nextRatio, size)
    setSizeNotice(generationResolution(nextSize) !== generationResolution(size)
      ? `该比例不支持原分辨率，已切换为 ${generationResolution(nextSize) || '自定义'}（${nextSize}）。` : '')
    setSize(nextSize)
  }

  useEffect(() => {
    mounted.current = true
    document.title = 'ImgHub · AI 创作'
    // 描述仅随本次登录跳转传递，恢复后从路由状态移除，避免退出后再次展示。
    if (location.state?.creationPrompt) navigate(location.pathname, { replace: true, state: null })
    return () => { mounted.current = false }
  }, [])

  useEffect(() => {
    if (!app.user) return
    const controller = new AbortController()
    setModelsError('')
    setModelsLoading(true)
    void api<AiModel[]>('/generations/models', { signal: controller.signal }).then(items => {
      if (controller.signal.aborted) return
      setModels(items)
      setModelId(previous => items.some(item => String(item.id) === previous) ? previous : String(items[0]?.id || ''))
    }).catch(error => { if (!controller.signal.aborted) setModelsError(message(error)) })
      .finally(() => { if (!controller.signal.aborted) setModelsLoading(false) })
    return () => controller.abort()
  }, [modelsRevision, app.user])

  useEffect(() => {
    setSize(previous => model?.sizes.includes(previous) ? previous : model?.defaultSize || '')
    setQuality(previous => model?.qualities.includes(previous) ? previous : model?.defaultQuality || '')
  }, [model])

  useEffect(() => {
    observedTask.current = null
  }, [app.user])

  // 历史列表独立请求和重试，慢列表不会占用任务状态的轮询周期。
  useEffect(() => {
    if (!app.user) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function refreshHistory() {
      if (document.hidden) { timer = setTimeout(refreshHistory, 5000); return }
      const beforeRequest = observedTask.current
      try {
        const records = await api<Page<Generation>>('/generations?page=' + page + '&pageSize=12', { signal: controller.signal })
        if (controller.signal.aborted) return
        const latest = observedTask.current
        // 请求途中已经观察到更新状态时，不能被较早发出的历史响应覆盖。
        if (latest && latest !== beforeRequest) {
          records.records = records.records.map(item => updateHistoryTask(item, latest))
        }
        setHistory(records)
        setHistoryError('')
      } catch (error) {
        if (!controller.signal.aborted) {
          setHistoryError(message(error))
          timer = setTimeout(refreshHistory, 5000)
        }
      } finally {
        if (!controller.signal.aborted) setHistoryLoading(false)
      }
    }
    void refreshHistory()
    return () => { controller.abort(); clearTimeout(timer) }
  }, [page, historyRevision, revision, app.user])

  useEffect(() => {
    if (!app.user) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    let lastTask = active
    let shouldPoll = !!active || !!pending.current
    // 进入、主动刷新、切回页面或提交后读取额度；任务中间状态不改变额度。
    void appRef.current.refreshQuota(controller.signal).catch(() => {})

    async function refresh() {
      if (document.hidden) { timer = setTimeout(refresh, 5000); return }
      try {
        let observed = lastTask
          ? await api<Generation>('/generations/' + lastTask.id, { signal: controller.signal })
          : await api<Generation | null>('/generations/active', { signal: controller.signal })
        // 只有提交响应丢失、尚不知道任务 ID 时才用最近历史确认；正常任务轮询不等列表。
        if (!observed && pending.current) {
          const recent = await api<Page<Generation>>('/generations?page=1&pageSize=12', { signal: controller.signal })
          observed = recent.records.find(item => item.requestId === pending.current?.requestId) || null
        }
        if (controller.signal.aborted) return
        const current = observed && runningStatuses.includes(observed.status) ? observed : null
        const confirmed = !!pending.current && observed?.requestId === pending.current.requestId
        setActive(current)
        if (observed) {
          observedTask.current = observed
          const result = observed
          const updateViewed = (previous: Generation | null) => previous?.id === result.id ? result : previous
          setLatestTask(result)
          setDetail(updateViewed)
          setHistory(previous => previous ? { ...previous, records: previous.records.map(item => updateHistoryTask(item, result)) } : previous)
        }
        if (confirmed) {
          pending.current = null
          setUncertain(false)
          setPage(1)
        }
        // 完成/失败及不明确提交确认后才重新读列表与额度；中间状态直接更新当前行。
        if ((lastTask && !current) || confirmed) {
          setHistoryRevision(value => value + 1)
          void appRef.current.refreshQuota(controller.signal).catch(() => {})
        }
        lastTask = current
        shouldPoll = !!current || !!pending.current
        if (!pending.current) setError('')
      } catch (error) {
        if (!controller.signal.aborted) setError(message(error))
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
          if (shouldPoll) timer = setTimeout(refresh, 2000)
        }
      }
    }
    void refresh()
    const onFocus = () => { clearTimeout(timer); setRevision(value => value + 1) }
    window.addEventListener('focus', onFocus)
    return () => { controller.abort(); clearTimeout(timer); window.removeEventListener('focus', onFocus) }
  }, [revision, app.user])

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!app.user) { navigate('/login', { state: { from: '/', creationPrompt: prompt } }); return }
    if (submitting.current || active || (!model && !pending.current)) return
    submitting.current = true
    setBusy(true)
    setError('')
    const request = pending.current || { requestId: crypto.randomUUID(), prompt: prompt.trim(), modelId: model!.id, size, quality }
    pending.current = request
    try {
      const result = await api<Generation>('/generations', { method: 'POST', body: JSON.stringify(request) })
      if (!mounted.current) return
      pending.current = null
      setUncertain(false)
      setLatestTask(result)
      if (runningStatuses.includes(result.status)) setActive(result)
      setPage(1)
      setRevision(value => value + 1)
    } catch (error) {
      if (!mounted.current) return
      // 连接中断或服务器异常可能发生在任务落库后；保留原请求ID和参数。
      const unknown = !(error instanceof ApiError) || error.code === 0 || error.code >= 500
      if (!unknown) pending.current = null
      setUncertain(unknown)
      setError(unknown ? '尚未确认提交结果。请点击“确认本次提交”，会沿用同一请求，不会重复生成。' : message(error))
      setRevision(value => value + 1)
    } finally {
      submitting.current = false
      if (mounted.current) setBusy(false)
    }
  }

  function reuse(task: Generation) {
    if (busy || uncertain) return
    setPrompt(task.prompt)
    setDetail(null)
    requestAnimationFrame(() => document.getElementById('creation-prompt')?.focus())
  }

  async function copyPrompt(text: string) {
    try { await navigator.clipboard.writeText(text); app.notify('提示词已复制') }
    catch { app.notify('复制失败，请选中提示词后手动复制', true) }
  }

  function renderResult(task: Generation) {
    const running = runningStatuses.includes(task.status)
    const downloadUrl = task.image ? new URL(task.image.url) : null
    // 七牛使用 attname 返回附件下载响应，跨域图片无需先 fetch 到浏览器内存。
    if (task.image && downloadUrl) downloadUrl.searchParams.set('attname', task.image.name)
    return <section className="creation-result" aria-label="生成结果">
      <div className={'creation-detail-preview' + (running ? ' is-running' : '')}>
        <div className="creation-detail-image-tools">
          <div className="creation-detail-badges"><span>{imageAspectRatio(task.size)}</span><span>{task.size.replace('x', '×')}</span></div>
          {task.image && <a className="creation-detail-download" href={downloadUrl?.href} download={task.image.name} aria-label="下载图片" title="下载图片"><DownloadSimple size={18} /></a>}
        </div>
        {task.image ? <div className="creation-image"><img src={task.image.url} alt={task.prompt} /></div> :
          <div className="creation-placeholder" aria-live="polite">
            <div className="creation-detail-loader"><Sparkle size={38} weight="thin" aria-hidden="true" /></div>
            <h3>{labels[task.status]}</h3>
            <p>{task.status === 'GENERATING' || task.status === 'QUEUED' ? '你可以离开页面，任务会在后台继续。' : task.status === 'SAVING' ? '图片已生成，正在保存到你的图库。' : task.status === 'SUCCEEDED' ? '生成已完成，图片已被删除。' : task.errorMessage || '本次任务已结束，预留额度已释放。'}</p>
          </div>}
      </div>
      <div className="creation-detail-panel">
        <div className="creation-detail-content">
          <section className="creation-detail-prompt" aria-labelledby="creation-detail-prompt-title">
            <div className="creation-detail-section-heading"><h3 id="creation-detail-prompt-title">输入内容</h3><button className="icon-button" aria-label="复制提示词" title="复制提示词" onClick={() => void copyPrompt(task.prompt)}><Copy size={15} /></button></div>
            <p tabIndex={0} aria-label="完整提示词">{task.prompt}</p>
          </section>
          <section className="creation-detail-settings" aria-labelledby="creation-detail-settings-title">
            <h3 id="creation-detail-settings-title">参数配置</h3>
            <dl className="creation-detail-parameters">
              <div className="creation-detail-model"><dt>生成模型</dt><dd>{task.modelName}</dd></div>
              <div><dt>尺寸</dt><dd>{task.size.replace('x', '×')}</dd></div>
              <div><dt>质量</dt><dd>{qualityNames[task.quality] || task.quality}</dd></div>
              <div><dt>画面比例</dt><dd>{imageAspectRatio(task.size)}</dd></div>
              <div><dt>分辨率</dt><dd>{generationResolution(task.size) || '自定义'}</dd></div>
              {task.image?.type && <div><dt>图片格式</dt><dd>{task.image.type.toUpperCase()}</dd></div>}
              <div><dt>生成数量</dt><dd>1 张</dd></div>
            </dl>
          </section>
          <div className="creation-detail-meta"><span className={'creation-detail-status status-' + task.status.toLowerCase()} role="status"><i aria-hidden="true" />{labels[task.status]}</span><time dateTime={task.createTime.replace(' ', 'T')}>创建于 {task.createTime}</time></div>
          {task.image && <p className="creation-detail-saved">已保存到我的图片</p>}
        </div>
        <footer className="creation-detail-actions">
          <button className="button creation-detail-reuse" disabled={busy || uncertain} onClick={() => reuse(task)}><ArrowUUpLeft size={17} />复用描述</button>
          {task.image && <button className="button button-secondary" onClick={() => void app.copyUrl(task.image!.url)}><Copy size={16} />复制链接</button>}
        </footer>
      </div>
    </section>
  }

  return <main id="main" className="creation-page hero">
    <Ambient target={workspace} />
    <header className="creation-heading hero-heading">
      <span className="eyebrow"><span className="tiny-line" />AI 创作空间<span className="tiny-line" /></span>
      <h1>让想象，<span>即刻成真。</span></h1>
      <p>写下你的想法，让 AI 把它变成一张图片。</p>
    </header>
    <div className="creation-workspace" ref={workspace}>
      <span className="upload-rim" aria-hidden="true" />
      <form className="creation-form" onSubmit={submit} aria-busy={busy}>
        <label className="visually-hidden" htmlFor="creation-prompt">画面描述</label>
        <textarea id="creation-prompt" value={prompt} onChange={event => setPrompt(event.target.value)} maxLength={4000} required
          disabled={busy || uncertain} placeholder="描述你想创作的画面…&#10;例如：海边的书店，午后阳光，胶片摄影质感。" />
        <div className="creation-toolbar">
          <div className="creation-options">
            <div className="creation-model-option">
              <label className="visually-hidden" htmlFor="creation-model">生成模型</label>
              <select className="format-select" id="creation-model" value={modelId} onChange={event => { setModelId(event.target.value); setSizeNotice('') }} disabled={!app.user || !models.length || busy || uncertain}>
                {!models.length && <option value="">{!app.user ? '登录后选择模型' : modelsLoading ? '正在加载模型…' : '暂无可用模型'}</option>}
                {models.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </div>
            <CreationRatioPicker value={ratio} options={ratios} size={size} sizes={ratioSizes} onChange={changeRatio}
              onSizeChange={value => { setSize(value); setSizeNotice('') }} disabled={!model || busy || uncertain} />
            <div><label className="visually-hidden" htmlFor="creation-quality">质量</label><select className="format-select" id="creation-quality" value={quality} onChange={event => setQuality(event.target.value)} disabled={!model || busy || uncertain}>
              {!model && <option value="">质量</option>}
              {model?.qualities.map(value => <option key={value} value={value}>{qualityNames[value] || value}</option>)}
            </select></div>
          </div>
          {app.user ? <button className="creation-submit" title={uncertain ? '确认本次提交' : '生成图片'} aria-describedby="creation-submit-status" disabled={busy || loading || !!active || (!model && !uncertain) || !prompt.trim() || (!uncertain && (!app.quota || !!app.quotaError || app.quota.remaining < 1))}>
            <span className="visually-hidden">{uncertain ? '确认本次提交' : '生成图片'}</span><ArrowUp size={21} aria-hidden="true" />
          </button> : <button type="button" className="creation-submit" title="登录创作" onClick={() => navigate('/login', { state: { from: '/', creationPrompt: prompt } })}><span className="visually-hidden">登录创作</span><ArrowUp size={21} aria-hidden="true" /></button>}
        </div>
      </form>
    </div>
    <div className="creation-caption">
      <div className="creation-quota"><span>单张生成 · 消耗 1 次</span><QuotaStatus app={app} /></div>
      <span id="creation-submit-status" role="status">{!app.user ? '登录创作' : busy ? '正在提交…' : active ? '任务进行中' : uncertain ? '确认本次提交' : app.quotaError ? '额度待更新' : app.quota?.remaining === 0 ? '额度已用完' : '生成图片'}</span>
    </div>
    {sizeNotice && <p className="creation-help creation-notice" role="status">{sizeNotice}</p>}
    {modelsError && <div className="creation-error creation-alert" role="alert">{modelsError}<button className="quiet-link" onClick={() => setModelsRevision(value => value + 1)}>重新加载模型</button></div>}
    {app.user && !models.length && !modelsError && !modelsLoading && <p className="creation-help creation-notice">管理员配置并启用模型后，即可开始创作。</p>}
    {app.user && <p className="creation-help creation-notice">与上传共用额度。生成并保存成功后扣除，失败返还预留额度。</p>}
    {detail && <Modal title="创作记录详情" className="creation-detail-modal" onClose={() => setDetail(null)}>{renderResult(detail)}</Modal>}
    {error && <div className="creation-error creation-alert" role="alert">{error}<button className="quiet-link" onClick={() => setRevision(value => value + 1)}>刷新状态</button></div>}
    {historyError && <div className="creation-error creation-alert" role="alert">历史记录加载失败：{historyError}<button className="quiet-link" onClick={() => setHistoryRevision(value => value + 1)}>刷新历史</button></div>}
    {app.user && (historyLoading || !!visibleRecords.length) && <section className="creation-history" aria-labelledby="creation-history-title">
      <header><div><h2 id="creation-history-title">创作记录</h2><p>每一个想法，都有迹可循。</p></div><span>{Math.max(history?.total || 0, visibleRecords.length)} 次创作</span></header>
      {!visibleRecords.length ? <p className="creation-history-empty">{historyLoading ? '正在加载…' : '还没有创作记录，试着生成第一张图片。'}</p> :
        <div className="creation-history-grid">{visibleRecords.map(task => {
          const running = runningStatuses.includes(task.status)
          return <button className={'creation-history-item' + (running ? ' is-running' : '') + (detail?.id === task.id ? ' is-selected' : '')} key={task.id} onClick={() => setDetail(task)} aria-haspopup="dialog">
            <div className="creation-history-thumb">{task.image ? <img src={task.image.preview} alt="" loading="lazy" /> : <Sparkle size={24} weight="light" aria-hidden="true" />}</div>
            <div className="creation-history-copy"><span className={'creation-status status-' + task.status.toLowerCase()} aria-live="polite">{labels[task.status]}{running && <span className="creation-status-dots" aria-hidden="true"><i /><i /><i /></span>}</span><p>{task.prompt}</p><span>{task.modelName} · {task.createTime}</span></div>
          </button>
        })}</div>}
      {history && history.pages > 1 && <div className="library-pagination"><button className="button button-secondary" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button><span>{page} / {history.pages}</span><button className="button button-secondary" disabled={page >= history.pages} onClick={() => setPage(value => value + 1)}>下一页</button></div>}
    </section>}
    <section className="creation-upload-entry" aria-label="上传已有图片"><div><h2>已经有想分享的图片？</h2><p>上传本地图片，与 AI 作品一起保存在「我的图片」。</p></div><Link className="button button-secondary" to="/upload">上传图片 <ArrowUpRight size={16} /></Link></section>

  </main>
}
