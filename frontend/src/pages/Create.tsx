import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { Copy } from '@phosphor-icons/react/dist/csr/Copy'
import type { AppState } from '../App'
import type { AiModel, Generation, Page } from '../lib/types'
import { api, ApiError } from '../lib/api'
import { GENERATION_RESOLUTIONS, generationResolution, generationSizeForRatio, imageAspectRatio } from '../lib/rules'
import { Modal } from '../components/Modal'
import { Ambient } from '../components/Ambient'
import { QuotaStatus } from '../components/QuotaStatus'
import './create.css'

const labels: Record<Generation['status'], string> = {
  QUEUED: '等待生成', GENERATING: '正在生成', SAVING: '正在保存',
  SAVE_FAILED: '等待重试保存', SUCCEEDED: '已完成', FAILED: '生成失败', EXPIRED: '结果已过期', ABANDONED: '已放弃',
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
  const [selected, setSelected] = useState<Generation | null>(null)
  const [detail, setDetail] = useState<Generation | null>(null)
  const [history, setHistory] = useState<Page<Generation> | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(!!app.user)
  const [error, setError] = useState('')
  const [modelsError, setModelsError] = useState('')
  const [modelsLoading, setModelsLoading] = useState(!!app.user)
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [abandon, setAbandon] = useState<Generation | null>(null)
  const [revision, setRevision] = useState(0)
  const pending = useRef<Submission | null>(null)
  const submitting = useRef(false)
  const mounted = useRef(false)
  const appRef = useRef(app)
  appRef.current = app
  const model = models.find(item => String(item.id) === modelId)
  const shown = selected
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
  }, [revision, app.user])

  useEffect(() => {
    setSize(previous => model?.sizes.includes(previous) ? previous : model?.defaultSize || '')
    setQuality(previous => model?.qualities.includes(previous) ? previous : model?.defaultQuality || '')
  }, [model])

  useEffect(() => {
    if (!app.user) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    async function refresh() {
      if (document.hidden) { timer = setTimeout(refresh, 5000); return }
      // 额度独立读取，任务或历史接口慢时也能及时展示。
      void appRef.current.refreshQuota(controller.signal).catch(() => {})
      try {
        const [current, records] = await Promise.all([
          api<Generation | null>('/generations/active', { signal: controller.signal }),
          api<Page<Generation>>('/generations?page=' + page + '&pageSize=12', { signal: controller.signal }),
        ])
        if (controller.signal.aborted) return
        setActive(current)
        setHistory(records)
        const submitted = pending.current
          ? current?.requestId === pending.current.requestId ? current : records.records.find(item => item.requestId === pending.current?.requestId)
          : null
        if (submitted) {
          pending.current = null
          setUncertain(false)
        }
        // 只更新本次提交或主动查看的记录；刷新页面不自动展开历史或未完成任务。
        const updateViewed = (previous: Generation | null) => previous
          ? current?.id === previous.id ? current : records.records.find(item => item.id === previous.id) || previous
          : null
        setSelected(previous => submitted || updateViewed(previous))
        setDetail(updateViewed)
        if (!pending.current) setError('')
      } catch (error) {
        if (!controller.signal.aborted) setError(message(error))
      } finally {
        if (!controller.signal.aborted) {
          setLoading(false)
          // ponytail: 页面可见时每5秒查询两次；规模扩大再改为服务端推送。
          timer = setTimeout(refresh, 5000)
        }
      }
    }
    void refresh()
    const onFocus = () => { clearTimeout(timer); setRevision(value => value + 1) }
    window.addEventListener('focus', onFocus)
    return () => { controller.abort(); clearTimeout(timer); window.removeEventListener('focus', onFocus) }
  }, [page, revision, app.user])

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
      setSelected(result)
      if (['QUEUED', 'GENERATING', 'SAVING', 'SAVE_FAILED'].includes(result.status)) setActive(result)
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

  async function action(task: Generation, path: 'retry-save' | 'abandon') {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    try {
      const result = await api<Generation>('/generations/' + task.id + '/' + path, { method: 'POST' })
      if (!mounted.current) return
      if (detail?.id === result.id) setDetail(previous => previous?.id === result.id ? result : previous)
      else setSelected(result)
      setActive(path === 'abandon' ? null : result)
      setAbandon(null)
      setRevision(value => value + 1)
    } catch (error) { if (mounted.current) setError(message(error)) }
    finally { submitting.current = false; if (mounted.current) setBusy(false) }
  }

  function reuse(task: Generation) {
    if (busy || uncertain) return
    setPrompt(task.prompt)
    setDetail(null)
    requestAnimationFrame(() => document.getElementById('creation-prompt')?.focus())
  }

  function renderResult(task: Generation, inDialog = false) {
    return <section className="creation-result" aria-label="生成结果">
        <header><h2>{labels[task.status]}</h2><span>{task.modelName}</span></header>
        {task.image ? <>
          {inDialog ? <div className="creation-image"><img src={task.image.url} alt={task.prompt} /></div>
            : <button className="creation-image" onClick={() => app.setPreview(task.image!)} aria-label="查看生成图片"><img src={task.image.url} alt={task.prompt} /></button>}
          <div className="creation-result-bottom"><span>已保存到我的图片</span><button className="button button-secondary" onClick={() => void app.copyUrl(task.image!.url)}><Copy size={16} />复制链接</button></div>
        </> : <div className="creation-placeholder" aria-live="polite">
          <Sparkle size={48} weight="thin" aria-hidden="true" />
          <h3>{labels[task.status]}</h3>
          <p>{task.status === 'GENERATING' || task.status === 'QUEUED' ? '你可以离开页面，任务会在后台继续。' : task.status === 'SAVING' ? '图片已生成，正在保存到你的图库。' : task.status === 'SUCCEEDED' ? '生成已完成，图片已被删除。' : task.errorMessage || '本次任务已结束，预留额度已释放。'}</p>
          {task.status === 'SAVE_FAILED' && <><p>结果保留至 {task.resultExpiresAt}（北京时间）</p><div className="creation-result-actions"><button className="button button-primary" disabled={busy} onClick={() => void action(task, 'retry-save')}>重试保存</button><button className="button button-secondary" disabled={busy} onClick={() => { setDetail(null); setAbandon(task) }}>放弃结果</button></div><p>重试仅保存这张图片，不会再次生成。</p></>}
        </div>}
        <div className="creation-result-meta"><p>{task.prompt}</p><span>{imageAspectRatio(task.size)} · {generationResolution(task.size) && `${generationResolution(task.size)} · `}{task.size} · {qualityNames[task.quality] || task.quality}</span><time>{task.createTime}</time><button className="quiet-link" disabled={busy || uncertain} onClick={() => reuse(task)}>复用描述</button></div>
    </section>
  }

  return <main id="main" className="creation-page hero">
    <Ambient target={workspace} active={busy} />
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
        <div className="creation-field-note"><span>描述主体、环境、光线和风格</span><span>{prompt.length} / 4000</span></div>
        <div className="creation-options">
            <div className="creation-model-option">
              <label className="visually-hidden" htmlFor="creation-model">生成模型</label>
              <select id="creation-model" value={modelId} onChange={event => { setModelId(event.target.value); setSizeNotice('') }} disabled={!app.user || !models.length || busy || uncertain}>
                {!models.length && <option value="">{!app.user ? '登录后选择模型' : modelsLoading ? '正在加载模型…' : '暂无可用模型'}</option>}
                {models.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </div>
            <div><label className="visually-hidden" htmlFor="creation-size">画面比例</label><select id="creation-size" value={ratio} onChange={event => changeRatio(event.target.value)} disabled={!model || busy || uncertain}>
              {!model && <option value="">比例</option>}
              {ratios.map(value => <option key={value} value={value}>{value}</option>)}
            </select></div>
            <div className="creation-resolution-option"><label className="visually-hidden" htmlFor="creation-resolution">分辨率</label><select id="creation-resolution" value={size} onChange={event => { setSize(event.target.value); setSizeNotice('') }} disabled={!model || busy || uncertain}>
              {!model ? <option value="">分辨率</option> : <>
                {GENERATION_RESOLUTIONS.map(tier => {
                  const value = ratioSizes.find(item => generationResolution(item) === tier)
                  return <option key={tier} value={value || tier} disabled={!value}>{tier}{value ? ` · ${value.replace('x', '×')}` : ' · 当前比例不可用'}</option>
                })}
                {ratioSizes.filter(value => !generationResolution(value)).map(value => <option key={value} value={value}>自定义 · {value.replace('x', '×')}</option>)}
              </>}
            </select></div>
            <div><label className="visually-hidden" htmlFor="creation-quality">质量</label><select id="creation-quality" value={quality} onChange={event => setQuality(event.target.value)} disabled={!model || busy || uncertain}>
              {!model && <option value="">质量</option>}
              {model?.qualities.map(value => <option key={value} value={value}>{qualityNames[value] || value}</option>)}
            </select></div>
          </div>
        {sizeNotice && <p className="creation-help" role="status">{sizeNotice}</p>}
        <div className="upload-toolbar creation-toolbar">
          <div className="upload-info"><span className="upload-limit">单张生成 · 消耗 1 次</span><QuotaStatus app={app} /></div>
          {app.user ? <button className="button button-primary button-upload creation-submit" disabled={busy || loading || !!active || (!model && !uncertain) || !prompt.trim() || (!uncertain && (!app.quota || !!app.quotaError || app.quota.remaining < 1))}>
            <span>{busy ? '正在提交…' : active ? '任务进行中' : uncertain ? '确认本次提交' : app.quotaError ? '额度待更新' : app.quota?.remaining === 0 ? '额度已用完' : '生成图片'}</span><ArrowUp size={17} />
          </button> : <button type="button" className="button button-primary button-upload creation-submit" onClick={() => navigate('/login', { state: { from: '/', creationPrompt: prompt } })}><span>登录创作</span><ArrowUp size={17} /></button>}
        </div>
      </form>
    </div>
    {modelsError && <p className="creation-error creation-notice" role="alert">{modelsError}</p>}
    {app.user && !models.length && !modelsError && !modelsLoading && <p className="creation-help creation-notice">管理员配置并启用模型后，即可开始创作。</p>}
    {app.user && <p className="creation-help creation-notice">与上传共用额度。生成并保存成功后扣除，失败返还预留额度。</p>}
    {shown && renderResult(shown)}
    {detail && <Modal title="创作记录详情" className="creation-detail-modal" onClose={() => setDetail(null)}>{renderResult(detail, true)}</Modal>}
    {error && <div className="creation-error creation-alert" role="alert">{error}<button className="quiet-link" onClick={() => setRevision(value => value + 1)}>刷新状态</button></div>}
    {app.user && (loading || !!history?.records.length) && <section className="creation-history" aria-labelledby="creation-history-title">
      <header><div><h2 id="creation-history-title">创作记录</h2><p>每一个想法，都有迹可循。</p></div><span>{history?.total || 0} 次创作</span></header>
      {!history?.records.length ? <p className="creation-history-empty">{loading ? '正在加载…' : '还没有创作记录，试着生成第一张图片。'}</p> :
        <div className="creation-history-grid">{history.records.map(task => <button className={'creation-history-item' + (detail?.id === task.id ? ' is-selected' : '')} key={task.id} onClick={() => setDetail(task)} aria-haspopup="dialog">
          <div className="creation-history-thumb">{task.image ? <img src={task.image.preview} alt="" loading="lazy" /> : <Sparkle size={24} weight="light" />}</div>
          <div><span className={'creation-status status-' + task.status.toLowerCase()}>{labels[task.status]}</span><p>{task.prompt}</p><span>{task.modelName} · {task.createTime}</span></div>
        </button>)}</div>}
      {history && history.pages > 1 && <div className="library-pagination"><button className="button button-secondary" disabled={page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button><span>{page} / {history.pages}</span><button className="button button-secondary" disabled={page >= history.pages} onClick={() => setPage(value => value + 1)}>下一页</button></div>}
    </section>}
    <section className="creation-upload-entry" aria-label="上传已有图片"><div><h2>已经有想分享的图片？</h2><p>上传本地图片，与 AI 作品一起保存在「我的图片」。</p></div><Link className="button button-secondary" to="/upload">上传图片 <ArrowUpRight size={16} /></Link></section>
    {abandon && <Modal title="放弃这张生成结果？" onClose={() => { if (!busy) setAbandon(null) }}>
      <p className="creation-help">临时图片将被清除，预留的 1 次额度会释放。之后无法恢复这张结果。</p>
      <div className="modal-actions"><button className="button button-secondary" disabled={busy} onClick={() => setAbandon(null)}>继续保留</button><button className="button button-danger" disabled={busy} onClick={() => void action(abandon, 'abandon')}>{busy ? '处理中…' : '确认放弃'}</button></div>
    </Modal>}
  </main>
}
