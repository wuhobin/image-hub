import {lazy, Suspense, useEffect, useRef, useState} from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {ClockCounterClockwise} from '@phosphor-icons/react/dist/csr/ClockCounterClockwise'
import { ArrowUpRight } from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import { ArrowUp } from '@phosphor-icons/react/dist/csr/ArrowUp'
import { ImageSquare } from '@phosphor-icons/react/dist/csr/ImageSquare'
import { X } from '@phosphor-icons/react/dist/csr/X'
import {Warning} from '@phosphor-icons/react/dist/csr/Warning'
import type { AppState } from '../App'
import type {AiModel, CreationPreset, Generation, GenerationHistoryFilter, ImageRecord, Page} from '../lib/types'
import { api, ApiError } from '../lib/api'
import { MAX_BYTES, fileError, generationResolution, generationSizeForRatio, imageAspectRatio } from '../lib/rules'
import { Ambient } from '../components/Ambient'
import { QuotaStatus } from '../components/QuotaStatus'
import {Modal} from '../components/Modal'
import { CreationRatioPicker } from '../components/CreationRatioPicker'
import './create.css'
import {readCreationOptions, saveCreationOptions} from '../lib/templates'
import type {TemplateDraft} from '../lib/templates'

const CreationCurrent = lazy(() => import('../components/CreationCurrent'))
import CreationRecords from '../components/CreationRecords'
const CreationDetail = lazy(() => import('../components/CreationDetail'))

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
type Submission = { requestId: string; prompt: string; modelId: number; size: string; quality: string; reference?: File; referenceImageId?: string }
type ReferenceImage = { file: File; preview: string } | { image: ImageRecord; preview: string }

/** 工作台与独立记录页复用任务轮询和详情操作；仅记录页请求分页，轮询不重放生成请求。 */
export default function Create({app, view = 'workspace'}: { app: AppState; view?: 'workspace' | 'history' }) {
    const historyView = view === 'history'
  const location = useLocation()
  const navigate = useNavigate()
    const creationPreset = useRef<CreationPreset | undefined>(location.state?.creationPreset)
  const workspace = useRef<HTMLDivElement>(null)
  const referenceInput = useRef<HTMLInputElement>(null)
    const [reference, setReference] = useState<ReferenceImage | null>(() => {
        const image = location.state?.referenceImage as ImageRecord | undefined
        return image ? {image, preview: image.preview} : null
    })
  const [referenceError, setReferenceError] = useState('')
  const [models, setModels] = useState<AiModel[]>([])
  const [modelId, setModelId] = useState('')
    const [templateDraft, setTemplateDraft] = useState<TemplateDraft | undefined>(location.state?.templateDraft)
  const [prompt, setPrompt] = useState(() => typeof location.state?.creationPrompt === 'string' ? location.state.creationPrompt.slice(0, 4000) : '')
  const [size, setSize] = useState('')
  const [quality, setQuality] = useState('')
  const [sizeNotice, setSizeNotice] = useState('')
  const [active, setActive] = useState<Generation | null>(null)
  const [latestTask, setLatestTask] = useState<Generation | null>(null)
  const [detail, setDetail] = useState<Generation | null>(null)
    const [deleting, setDeleting] = useState<Generation | null>(null)
    const [deletingBusy, setDeletingBusy] = useState(false)
    const [deleteError, setDeleteError] = useState('')
  const [history, setHistory] = useState<Page<Generation> | null>(null)
  const [page, setPage] = useState(1)
    const [historyFilter, setHistoryFilter] = useState<GenerationHistoryFilter>({
        status: 'done',
        keyword: '',
        order: 'desc'
    })
  const [loading, setLoading] = useState(!!app.user)
    const [historyLoading, setHistoryLoading] = useState(!!app.user || app.initializing)
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
    const historyDeletion = useRef(0)
    const focusAfterDelete = useRef(false)
  const pending = useRef<Submission | null>(null)
  const submitting = useRef(false)
  const mounted = useRef(false)
  const appRef = useRef(app)
  appRef.current = app
  const model = models.find(item => String(item.id) === modelId)
    // 工作台只展示本次提交或进入页面时恢复的活动任务，不加载已完成的历史作品。
  const currentTask = active || latestTask
  const historyRecords = history?.records || []
    // 记录页只使用服务端筛选后的结果，不能把不匹配状态的活动任务插入当前页。
    const visibleRecords = historyRecords
  const ratio = imageAspectRatio(size)
  const ratios = [...new Set(model?.sizes.map(imageAspectRatio) || [])]
  const ratioSizes = model?.sizes.filter(value => imageAspectRatio(value) === ratio) || []

    // 从模板库返回时保留当前账号本标签页的参数；模板本身不改变模型或质量。
    useEffect(() => {
        if (app.user && model && model.sizes.includes(size) && model.qualities.includes(quality)) {
            saveCreationOptions(app.user, {modelId, size, quality})
        }
    }, [app.user, model, modelId, size, quality])

  useEffect(() => {
    if (!reference) return
    return () => {
      if (appRef.current.preview?.preview === reference.preview) appRef.current.setPreview(null)
      if ('file' in reference) URL.revokeObjectURL(reference.preview)
    }
  }, [reference])

  function selectReference(file?: File) {
    if (!file || busy || uncertain) return
    const invalid = fileError(file) || (file.type === 'image/gif' ? '参考图仅支持 JPG、PNG、WebP 格式' : '')
    setReferenceError(invalid)
    if (invalid) return
    setReference({ file, preview: URL.createObjectURL(file) })
  }

  function changeRatio(nextRatio: string) {
    const nextSize = generationSizeForRatio(model?.sizes || [], nextRatio, size)
    setSizeNotice(generationResolution(nextSize) !== generationResolution(size)
      ? `该比例不支持原分辨率，已切换为 ${generationResolution(nextSize) || '自定义'}（${nextSize}）。` : '')
    setSize(nextSize)
  }

  useEffect(() => {
    mounted.current = true
      document.title = historyView ? 'ImgHub · 创作记录' : 'ImgHub · AI 创作'
      // 登录或记录页传来的描述、参考图只消费一次，避免浏览器返回时重新填入。
      if (!app.initializing && (location.state?.creationPrompt || location.state?.referenceImage)) {
          navigate(location.pathname, {replace: true, state: null})
          requestAnimationFrame(() => document.getElementById('creation-prompt')?.focus({preventScroll: true}))
      }
    return () => { mounted.current = false }
  }, [app.initializing])

  useEffect(() => {
      if (!app.user || historyView) return
    const controller = new AbortController()
    setModelsError('')
    setModelsLoading(true)
    void api<AiModel[]>('/generations/models', { signal: controller.signal }).then(items => {
      if (controller.signal.aborted) return
      setModels(items)
        const preset = creationPreset.current
        const selected = preset ? items.find(item => item.id === preset.modelId) || items[0] : undefined
        if (preset && selected) {
            setModelId(String(selected.id))
            setSize(selected.sizes.includes(preset.size) ? preset.size : selected.defaultSize)
            setQuality(selected.qualities.includes(preset.quality) ? preset.quality : selected.defaultQuality)
            setSizeNotice(selected.id !== preset.modelId || !selected.sizes.includes(preset.size) || !selected.qualities.includes(preset.quality)
                ? '原作品的部分模型或参数已不可用，已选择当前可用选项，请确认后生成。' : '已带入公开提示词和生成参数，请确认后生成。')
            creationPreset.current = undefined
        } else {
            const saved = readCreationOptions(app.user)
            setModelId(previous => items.some(item => String(item.id) === previous) ? previous
                : items.some(item => String(item.id) === saved.modelId) ? saved.modelId : String(items[0]?.id || ''))
            setSize(previous => previous || saved.size)
            setQuality(previous => previous || saved.quality)
        }
    }).catch(error => { if (!controller.signal.aborted) setModelsError(message(error)) })
      .finally(() => { if (!controller.signal.aborted) setModelsLoading(false) })
    return () => controller.abort()
  }, [modelsRevision, app.user, historyView])

  useEffect(() => {
      // 模型尚未加载时不清空已恢复的参数，加载完成后再校验是否仍然可用。
      if (!model) return
    setSize(previous => model?.sizes.includes(previous) ? previous : model?.defaultSize || '')
    setQuality(previous => model?.qualities.includes(previous) ? previous : model?.defaultQuality || '')
  }, [model])

  useEffect(() => {
    observedTask.current = null
  }, [app.user])

    useEffect(() => {
        if (deleting || !focusAfterDelete.current) return
        focusAfterDelete.current = false
        // 等确认弹窗卸载、解除原生 dialog 的焦点限制后，再聚焦列表标题。
        const frame = requestAnimationFrame(() => document.getElementById('creation-history-title')?.focus({preventScroll: true}))
        return () => cancelAnimationFrame(frame)
    }, [deleting])

    // 只有记录页请求分页；工作台的任务轮询不依赖历史列表。
  useEffect(() => {
      if (!app.user || !historyView) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
      setHistoryLoading(true)
    async function refreshHistory() {
      if (document.hidden) { timer = setTimeout(refreshHistory, 5000); return }
      const beforeRequest = observedTask.current
        const beforeDeletion = historyDeletion.current
      try {
          const search = new URLSearchParams({
              page: String(page), pageSize: '12', status: historyFilter.status,
              keyword: historyFilter.keyword.trim(), order: historyFilter.order
          })
          const records = await api<Page<Generation>>('/generations?' + search, {signal: controller.signal})
          if (controller.signal.aborted || beforeDeletion !== historyDeletion.current) return
        const latest = observedTask.current
        // 请求途中已经观察到更新状态时，不能被较早发出的历史响应覆盖。
        if (latest && latest !== beforeRequest) {
          records.records = records.records.map(item => updateHistoryTask(item, latest))
        }
          if (page > Math.max(1, records.pages)) {
              setPage(Math.max(1, records.pages))
              return
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

      timer = setTimeout(refreshHistory, historyFilter.keyword.trim() ? 250 : 0)
    return () => { controller.abort(); clearTimeout(timer) }
  }, [page, historyFilter, historyRevision, revision, app.user, historyView])

  useEffect(() => {
    if (!app.user) return
    const controller = new AbortController()
    let timer: ReturnType<typeof setTimeout>
    let lastTask = active
    let shouldPoll = !!active || !!pending.current
      // 进入或切回页面优先复用 30 秒内的积分；业务变化另行强制刷新。
      void appRef.current.refreshQuota(controller.signal, false).catch(() => {
      })

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
        // 完成/失败及不明确提交确认后才重新读列表与积分；中间状态直接更新当前行。
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
      if (app.initializing) return
      if (!app.user) {
          navigate('/login', {state: {from: '/', creationPrompt: prompt, templateDraft}});
          return
      }
    if (submitting.current || active || (!pending.current && (!model || !prompt.trim()))) return
    submitting.current = true
    setBusy(true)
    setError('')
    const request = pending.current || { requestId: crypto.randomUUID(), prompt: prompt.trim(), modelId: model!.id, size, quality, reference: reference && 'file' in reference ? reference.file : undefined, referenceImageId: reference && 'image' in reference ? reference.image.id : undefined }
    pending.current = request
    try {
      // 不明确提交沿用同一份文件与 requestId；参数与参考图一次提交，避免提前上传产生闲置文件。
      const { reference: referenceFile, ...param } = request
      let body: string | FormData = JSON.stringify(param)
      if (referenceFile) {
        body = new FormData()
        body.append('param', new Blob([JSON.stringify(param)], { type: 'application/json' }))
        body.append('reference', referenceFile)
      }
      const result = await api<Generation>('/generations', { method: 'POST', body })
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
        // 提交会预留积分；即使用户已切页或提交结果不明确，也不能继续使用旧余额。
        if (appRef.current.user) void appRef.current.refreshQuota().catch(() => {
        })
      submitting.current = false
      if (mounted.current) setBusy(false)
    }
  }

  function reuse(task: Generation) {
    if (busy || uncertain) return
      if (historyView) {
          navigate('/', {state: {creationPrompt: task.prompt}});
          return
      }
    setPrompt(task.prompt)
    setReference(null)
    setReferenceError('')
    setDetail(null)
    requestAnimationFrame(() => document.getElementById('creation-prompt')?.focus())
  }

    async function confirmDelete() {
        if (!deleting || deletingBusy) return
        setDeletingBusy(true)
        setDeleteError('')
        try {
            try {
                await api<void>('/generations/' + encodeURIComponent(deleting.id), {method: 'DELETE'})
            } catch (error) {
                // 响应丢失后重试、或另一页面已删除时，将不存在视为操作已完成。
                if (!(error instanceof ApiError) || error.code !== 404) throw error
            }
            if (!mounted.current) return
            historyDeletion.current++
            setHistory(previous => previous ? {
                ...previous,
                records: previous.records.filter(item => item.id !== deleting.id)
            } : previous)
            setDetail(previous => previous?.id === deleting.id ? null : previous)
            setLatestTask(previous => previous?.id === deleting.id ? null : previous)
            if (observedTask.current?.id === deleting.id) observedTask.current = null
            focusAfterDelete.current = true
            setDeleting(null)
            setHistoryRevision(value => value + 1)
            app.notify('作品和创作记录已删除')
            void app.refreshUser().catch(() => {
            })
        } catch (error) {
            if (mounted.current) setDeleteError(message(error))
        } finally {
            if (mounted.current) setDeletingBusy(false)
        }
    }

  function editImage(task: Generation) {
    if (!task.image || busy || uncertain) return
    if (task.image.size > MAX_BYTES) { app.notify('参考图不能超过 10 MB', true); return }
      if (historyView) {
          navigate('/', {state: {referenceImage: task.image}});
          return
      }
    setReference({ image: task.image, preview: task.image.preview })
    setReferenceError('')
    setDetail(null)
    requestAnimationFrame(() => {
      workspace.current?.scrollIntoView({ block: 'center' })
      document.getElementById('creation-prompt')?.focus({ preventScroll: true })
    })
  }


    const currentRunning = currentTask && runningStatuses.includes(currentTask.status)

    return <main id="main"
                 className={historyView ? 'creation-records-page history-page' : 'creation-page hero' + (currentTask ? ' has-task' : '')}>
        {!historyView && <>
    <Ambient target={workspace} />
    <header className="creation-heading hero-heading">
      <span className="eyebrow"><span className="tiny-line" />AI 创作空间<span className="tiny-line" /></span>
      <h1>让想象，<span>即刻成真。</span></h1>
      <p>写下你的想法，让 AI 把它变成一张图片。</p>
    </header>
    <div className="creation-workspace" ref={workspace}>
        <div className="creation-template-entry">
            <span>{templateDraft ? '正在使用：' + templateDraft.title : '还没想好怎么描述？'}</span>
            <Link to={templateDraft ? '/templates?template=' + templateDraft.id : '/templates'}
                  state={templateDraft ? {templateDraft: {...templateDraft, prompt}} : undefined}>
                {templateDraft ? '重新填写模板 →' : '从场景模板开始 →'}
            </Link>
        </div>
      <span className="upload-rim" aria-hidden="true" />
      <form className="creation-form" onSubmit={submit} aria-busy={busy}>
        <input ref={referenceInput} className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp"
          tabIndex={-1} aria-label="选择参考图文件" disabled={!app.user || busy || uncertain}
          onChange={event => { selectReference(event.currentTarget.files?.[0]); event.currentTarget.value = '' }} />
        {reference && <div className="creation-reference">
          <button type="button" className="creation-reference-preview" aria-label="预览参考图" aria-haspopup="dialog"
            onClick={() => app.setPreview('image' in reference ? reference.image : { name: reference.file.name, preview: reference.preview, size: reference.file.size, width: 0, height: 0 })}>
            <img src={reference.preview} alt="已选择的参考图" />
          </button>
          <button type="button" className="icon-button creation-reference-remove" aria-label="移除参考图" title="移除参考图" disabled={busy || uncertain}
            onClick={() => { setReference(null); setReferenceError('') }}><X size={15} /></button>
        </div>}
        {referenceError && <p className="creation-reference-error" role="alert">{referenceError}</p>}
        <label className="visually-hidden" htmlFor="creation-prompt">画面描述</label>
          <textarea id="creation-prompt" value={prompt} onChange={event => {
              setPrompt(event.target.value)
              setTemplateDraft(previous => previous ? {
                  ...previous,
                  prompt: event.target.value,
                  manual: true
              } : undefined)
          }} maxLength={4000} aria-required="true"
                  disabled={app.initializing || busy || uncertain}
                  placeholder="描述你想创作的画面…&#10;例如：海边的书店，午后阳光，胶片摄影质感。"/>
        <div className="creation-toolbar">
          <div className="creation-options">
            <button type="button" className={'creation-reference-add' + (reference ? ' has-reference' : '')}
              aria-label={reference ? '替换参考图' : '添加参考图'} title={!app.user ? '登录后添加参考图' : '添加单张参考图，JPG / PNG / WebP，最大 10 MB'}
              disabled={!app.user || busy || uncertain} onClick={() => referenceInput.current?.click()}><ImageSquare size={19} /></button>
            <div className="creation-model-option">
              <label className="visually-hidden" htmlFor="creation-model">生成模型</label>
                <select className="format-select creation-model-select" id="creation-model" value={modelId}
                        title={model ? `${model.name} · ${model.pointsCost} 积分/张` : undefined} onChange={event => {
                    setModelId(event.target.value);
                    setSizeNotice('')
                }} disabled={!app.user || !models.length || busy || uncertain}>
                    {model && <button type="button">
                        <span className="creation-model-name">{model.name}</span>{' '}
                        <span className="creation-model-cost">{model.pointsCost} 积分/张</span>
                    </button>}
                  {!models.length && <option
                      value="">{app.initializing ? '正在加载模型…' : !app.user ? '登录后选择模型' : modelsLoading ? '正在加载模型…' : '暂无可用模型'}</option>}
                    {models.map(item => <option key={item.id} value={item.id}>
                        <span className="creation-model-name">{item.name}</span>{' '}
                        <span className="creation-model-cost">{item.pointsCost} 积分/张</span>
                    </option>)}
              </select>
            </div>
            <CreationRatioPicker value={ratio} options={ratios} size={size} sizes={ratioSizes} onChange={changeRatio}
              onSizeChange={value => { setSize(value); setSizeNotice('') }} disabled={!model || busy || uncertain} />
            <div><label className="visually-hidden" htmlFor="creation-quality">质量</label><select className="format-select" id="creation-quality" value={quality} onChange={event => setQuality(event.target.value)} disabled={!model || busy || uncertain}>
              {!model && <option value="">质量</option>}
              {model?.qualities.map(value => <option key={value} value={value}>{qualityNames[value] || value}</option>)}
            </select></div>
          </div>
            {app.initializing || app.user ?
                <button className="creation-submit" title={uncertain ? '确认本次提交' : '生成图片'}
                        aria-describedby="creation-submit-status"
                        disabled={app.initializing || busy || loading || !!active || (!model && !uncertain) || !prompt.trim() || (!uncertain && (!app.quota || !!app.quotaError || app.quota.remaining < (model?.pointsCost ?? 1)))}>
            <span className="visually-hidden">{uncertain ? '确认本次提交' : '生成图片'}</span><ArrowUp size={21} aria-hidden="true" />
                </button> : <button type="button" className="creation-submit" title="登录创作"
                                    onClick={() => navigate('/login', {
                                        state: {
                                            from: '/',
                                            creationPrompt: prompt,
                                            templateDraft
                                        }
                                    })}><span className="visually-hidden">登录创作</span><ArrowUp size={21}
                                                                                                  aria-hidden="true"/>
                </button>}
        </div>
      </form>
    </div>
    <div className="creation-caption">
      <div className="creation-quota"><span>{model ? `单张生成 · 消耗 ${model.pointsCost} 积分` : '选择模型查看消耗积分'}</span><QuotaStatus app={app} /></div>
        <span id="creation-submit-status"
              role="status">{app.initializing ? '正在加载…' : !app.user ? '登录创作' : busy ? '正在提交…' : active ? '任务进行中' : uncertain ? '确认本次提交' : app.quotaError ? '积分待更新' : app.quota && model && app.quota.remaining < model.pointsCost ? '积分不足' : '生成图片'}</span>
    </div>
    {sizeNotice && <p className="creation-help creation-notice" role="status">{sizeNotice}</p>}
    {modelsError && <div className="creation-error creation-alert" role="alert">{modelsError}<button className="quiet-link" onClick={() => setModelsRevision(value => value + 1)}>重新加载模型</button></div>}
    {app.user && !models.length && !modelsError && !modelsLoading && <p className="creation-help creation-notice">管理员配置并启用模型后，即可开始创作。</p>}
            {(app.initializing || app.user) &&
                <p className="creation-help creation-notice">与上传共用积分。生成并保存成功后扣除，失败返还预留积分。</p>}
            <nav className="creation-records-entry" aria-label="创作记录入口">
                <Link to={app.initializing || app.user ? '/creations' : '/login'}
                      state={app.user ? undefined : {from: '/creations'}}><ClockCounterClockwise size={16}
                                                                                                 aria-hidden="true"/>创作记录<ArrowUpRight
                    size={14} aria-hidden="true"/></Link>
            </nav>
            {currentTask && <Suspense fallback={null}>
                <CreationCurrent key={currentTask.id} task={currentTask} running={!!currentRunning}
                                 statusLabel={labels[currentTask.status]}
                                 qualityLabel={qualityNames[currentTask.quality] || currentTask.quality}
                                 disabled={busy || uncertain} onPreview={() => app.setPreview(currentTask.image!)}
                                 onEdit={() => editImage(currentTask)} onDetail={() => setDetail(currentTask)}/>
            </Suspense>}
        </>}
        {historyView && <header className="creation-records-heading">
            <div><span className="creation-records-eyebrow">A COLLECTION OF YOUR IDEAS</span>
                <h1>创作记录</h1><p>把灵感留在这里。回看每一次想象成形。</p></div>
            <Link className="button button-primary" to="/">开始创作<ArrowUpRight size={16} aria-hidden="true"/></Link>
        </header>}
        {detail && <Suspense fallback={null}><CreationDetail task={detail} app={app}
                                                             running={runningStatuses.includes(detail.status)}
                                                             statusLabel={labels[detail.status]}
                                                             qualityLabel={qualityNames[detail.quality] || detail.quality}
                                                             disabled={busy || uncertain}
                                                             onReuse={reuse} onEdit={editImage}
                                                             onChanged={updated => {
                                                                 setDetail(previous => previous?.id === updated.id ? updated : previous)
                                                                 setLatestTask(previous => previous?.id === updated.id ? updated : previous)
                                                                 setHistory(previous => previous ? {
                                                                     ...previous,
                                                                     records: previous.records.map(item => item.id === updated.id ? updated : item)
                                                                 } : previous)
                                                             }}
                                                             onClose={() => setDetail(null)}/></Suspense>}
        {error && <div className="creation-error creation-alert" role="alert">{error}
            <button className="quiet-link" onClick={() => setRevision(value => value + 1)}>刷新状态</button>
        </div>}
        {historyView && historyError &&
            <div className="creation-error creation-alert" role="alert">历史记录加载失败：{historyError}
                <button className="quiet-link" onClick={() => setHistoryRevision(value => value + 1)}>刷新历史</button>
            </div>}
        {historyView && (app.initializing || app.user) &&
            <CreationRecords records={visibleRecords} history={history} loading={app.initializing || historyLoading}
                                 historyError={historyError}
                             selectedId={detail?.id} page={page} runningStatuses={runningStatuses}
                             filter={historyFilter}
                             onFilterChange={next => {
                                 setHistoryFilter(next)
                                 setPage(1)
                                 setHistoryLoading(true)
                                 setHistoryError('')
                             }}
                             onSelect={setDetail} onDelete={task => {
                setDeleteError('');
                setDeleting(task)
            }} onPageChange={setPage}/>
        }

        {deleting && <Modal title="删除这次创作？" className="confirm-modal creation-delete-modal"
                            onClose={() => {
                                if (!deletingBusy) setDeleting(null)
                            }}>
            <div className="delete-preview">
                {deleting.image && <img src={deleting.image.preview} alt=""/>}
                <div><strong>{deleting.prompt}</strong><span>{deleting.modelName} · {deleting.createTime}</span></div>
            </div>
            <p className="delete-warning"><Warning size={19} aria-hidden="true"/>
                创作记录和对应图片将同时删除，分享链接将失效。此操作无法撤销，已消耗的积分不退还。</p>
            {deleteError && <p className="creation-error" role="alert">{deleteError}</p>}
            <div className="modal-actions">
                <button type="button" className="button button-secondary" disabled={deletingBusy}
                        onClick={() => setDeleting(null)}>保留作品
                </button>
                <button type="button" className="button button-danger" disabled={deletingBusy}
                        onClick={confirmDelete}>{deletingBusy ? '删除中…' : '确认删除'}</button>
            </div>
        </Modal>}

  </main>
}
