import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import { adminApi } from '../../lib/admin/api'
import type { AiModelConfig, Page } from '../../lib/types'
import { Modal } from '../../components/Modal'
import '../create.css'
import { GENERATION_SIZES, generationResolution, imageAspectRatio } from '../../lib/rules'
import './models.css'

type Draft = Omit<AiModelConfig, 'id' | 'keyConfigured' | 'updateTime' | 'sizes' | 'qualities'> & { apiKey: string; sizes: string; qualities: string }
const initial: Draft = { name: '', modelCode: 'gpt-image-2', baseUrl: 'https://api.openai.com', imagesPath: '/v1/images/generations', apiKey: '', sizes: GENERATION_SIZES.join(','), defaultSize: '1024x1024', qualities: 'low,medium,high,auto', defaultQuality: 'medium', pointsCost: 1, sortOrder: 0, enabled: false }
const message = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试'
const options = (value: string) => value.split(/[,，\s]+/).map(item => item.trim()).filter(Boolean)

export default function AdminModels() {
  const [data, setData] = useState<Page<AiModelConfig> | null>(null)
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<AiModelConfig | 'new' | null>(null)
  const [deleting, setDeleting] = useState<AiModelConfig | null>(null)
  const [busy, setBusy] = useState(false)
  const [saved, setSaved] = useState('')
  const lock = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    void adminApi<Page<AiModelConfig>>('/ai-models?page=' + page + '&pageSize=20', { signal: controller.signal }).then(result => {
      if (controller.signal.aborted) return
      if (!result.records.length && page > 1) { setPage(value => value - 1); return }
      setData(result)
    }).catch(error => { if (!controller.signal.aborted) setError(message(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [page, revision])

  async function remove() {
    if (!deleting || lock.current) return
    lock.current = true
    setBusy(true)
    try {
      await adminApi('/ai-models/' + deleting.id, { method: 'DELETE' })
      setDeleting(null)
      setSaved('模型已删除')
      setRevision(value => value + 1)
    } catch (error) { setError(message(error)) }
    finally { lock.current = false; setBusy(false) }
  }

  return <div className="admin-models-page">
    <div className="admin-page-title"><div><span className="admin-eyebrow">AI 创作</span><h1>模型管理</h1><p>配置用户可选的生图模型，以及对应的尺寸、质量与单张消耗积分。</p></div><button className="button button-primary" onClick={() => setEditing('new')}><Plus size={17} />新增模型</button></div>
    <p className="model-intro">支持 OpenAI Images 兼容接口。密钥仅用于服务端调用；停用或删除不影响已提交的任务。</p>
    {saved && <p className="model-saved" role="status">{saved}</p>}
    {error && <p className="admin-error" role="alert">{error}<button className="quiet-link" onClick={() => setRevision(value => value + 1)}>重新加载</button></p>}
    <div className="models-list" aria-busy={loading}>
      {loading ? <div className="admin-state" role="status">正在读取模型…</div> : !data?.records.length ? <div className="admin-state">暂无模型，添加一个模型开始配置。</div> : data.records.map(model => <article className="model-row" key={model.id}>
        <div className="model-row-main"><span className={'model-enabled' + (model.enabled ? ' is-enabled' : '')}>{model.enabled ? '已启用' : '已停用'}</span><h2>{model.name}</h2><code>{model.modelCode}</code><p>{model.baseUrl}</p></div>
        <div className="model-row-options"><span>每张 {model.pointsCost} 积分</span><span>{model.sizes.length} 种尺寸 · {model.qualities.length} 种质量</span><span>默认 {imageAspectRatio(model.defaultSize)}（{model.defaultSize}）/ {model.defaultQuality}</span><span>{model.keyConfigured ? '密钥已配置' : '尚未配置密钥'}</span></div>
        <div className="model-row-actions"><button className="button button-secondary" onClick={() => setEditing(model)}>编辑</button><button className="quiet-link" onClick={() => { setError(''); setDeleting(model) }}>删除</button></div>
      </article>)}
    </div>
    {data && data.pages > 1 && <div className="library-pagination"><button className="button button-secondary" disabled={loading || page <= 1} onClick={() => setPage(value => value - 1)}>上一页</button><span>{page} / {data.pages}</span><button className="button button-secondary" disabled={loading || page >= data.pages} onClick={() => setPage(value => value + 1)}>下一页</button></div>}
    {editing && <ModelEditor model={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); setSaved('模型配置已保存'); setRevision(value => value + 1) }} />}
    {deleting && <Modal title={'删除“' + deleting.name + '”？'} onClose={() => { if (!busy) setDeleting(null) }}>
      <p className="creation-help">删除后无法用于新的创作，已提交的任务和历史记录会保留。此名称不能再次使用。</p>
      {error && <p role="alert" className="admin-error">{error}</p>}
      <div className="modal-actions"><button className="button button-secondary" disabled={busy} onClick={() => setDeleting(null)}>取消</button><button className="button button-danger" disabled={busy} onClick={() => void remove()}>{busy ? '删除中…' : '确认删除'}</button></div>
    </Modal>}
  </div>
}

function ModelEditor({ model, onClose, onSaved }: { model: AiModelConfig | 'new'; onClose: () => void; onSaved: () => void }) {
  const [draft, setDraft] = useState<Draft>(() => model === 'new' ? { ...initial } : { ...model, apiKey: '', sizes: model.sizes.join(','), qualities: model.qualities.join(',') })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
  function change<K extends keyof Draft>(key: K, value: Draft[K]) { setDraft(previous => ({ ...previous, [key]: value })) }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (lock.current) return
    const sizes = options(draft.sizes), qualities = options(draft.qualities)
    if (!sizes.includes(draft.defaultSize) || !qualities.includes(draft.defaultQuality)) { setError('默认尺寸和质量必须在允许的选项中'); return }
    lock.current = true
    setBusy(true)
    setError('')
    try {
      await adminApi('/ai-models' + (model === 'new' ? '' : '/' + model.id), { method: model === 'new' ? 'POST' : 'PUT', body: JSON.stringify({
        name: draft.name.trim(), modelCode: draft.modelCode.trim(), baseUrl: draft.baseUrl.trim(), imagesPath: draft.imagesPath.trim(),
        apiKey: draft.apiKey, sizes, qualities, defaultSize: draft.defaultSize, defaultQuality: draft.defaultQuality, enabled: draft.enabled, sortOrder: draft.sortOrder, pointsCost: draft.pointsCost,
      }) })
      setDraft(previous => ({ ...previous, apiKey: '' }))
      onSaved()
    } catch (error) { setError(message(error)) }
    finally { lock.current = false; setBusy(false) }
  }

  return <Modal title={model === 'new' ? '新增生图模型' : '编辑生图模型'} onClose={() => { if (!busy) onClose() }} className="model-modal">
    <form className="model-form" onSubmit={save} aria-busy={busy}>
      <fieldset disabled={busy}>
        <div className="model-form-grid">
          <div><label htmlFor="model-name">显示名称</label><input id="model-name" value={draft.name} onChange={event => change('name', event.target.value)} required maxLength={80} placeholder="例如 GPT-Image-2" /></div>
          <div><label htmlFor="model-code">模型代码</label><input id="model-code" value={draft.modelCode} onChange={event => change('modelCode', event.target.value)} required maxLength={120} /></div>
        </div>
        <label htmlFor="model-url">服务地址（Base URL）</label><input id="model-url" type="url" value={draft.baseUrl} onChange={event => change('baseUrl', event.target.value)} required maxLength={500} placeholder="https://api.openai.com" />
        <label htmlFor="model-path">生图接口路径</label><input id="model-path" value={draft.imagesPath} onChange={event => change('imagesPath', event.target.value)} required maxLength={200} />
        <p className="creation-help">服务地址与路径共同组成调用地址，例如 https://api.openai.com/v1/images/generations。</p>
        <label htmlFor="model-key">API Key {model !== 'new' && model.keyConfigured && <span>· 已配置，留空保留</span>}</label><input id="model-key" type="password" autoComplete="new-password" value={draft.apiKey} onChange={event => change('apiKey', event.target.value)} maxLength={512} placeholder={model !== 'new' && model.keyConfigured ? '留空保留原密钥' : '填写供应商密钥'} />
        <div className="model-form-grid">
          <div><label htmlFor="model-sizes">允许的尺寸（逗号分隔）</label><input id="model-sizes" value={draft.sizes} onChange={event => change('sizes', event.target.value)} required /><label htmlFor="model-default-size">默认尺寸</label><select id="model-default-size" value={draft.defaultSize} onChange={event => change('defaultSize', event.target.value)}>{[...new Set([...options(draft.sizes), draft.defaultSize])].map(value => <option key={value} value={value}>{imageAspectRatio(value)} · {generationResolution(value) || '自定义'}（{value}）</option>)}</select></div>
          <div><label htmlFor="model-qualities">允许的质量（逗号分隔）</label><input id="model-qualities" value={draft.qualities} onChange={event => change('qualities', event.target.value)} required /><label htmlFor="model-default-quality">默认质量</label><select id="model-default-quality" value={draft.defaultQuality} onChange={event => change('defaultQuality', event.target.value)}>{[...new Set([...options(draft.qualities), draft.defaultQuality])].map(value => <option key={value}>{value}</option>)}</select></div>
        </div>
        <p className="creation-help">用户端按比例和分辨率展示；同一比例可配置多个像素尺寸（如 1280x720、2048x1152、3840x2160）。非预设尺寸显示为自定义。GPT-Image-2 的宽高须为 16 的倍数，长短边比例不超过 3:1，总像素为 655360～8294400，单边不超过 3840。质量选项：low、medium、high、auto。</p>
        <label htmlFor="model-points-cost">单张消耗积分</label><input id="model-points-cost" type="number" min={1} max={2147483647} step={1} value={draft.pointsCost} onChange={event => change('pointsCost', Number(event.target.value))} required />
        <p className="creation-help">填写正整数，默认 1 积分。提交时预留，生成并保存成功后扣除，失败释放。修改仅影响新提交的任务。</p>
        <div className="model-form-grid"><div><label htmlFor="model-sort">排序（数字越小越靠前）</label><input id="model-sort" type="number" min={0} max={9999} step={1} value={draft.sortOrder} onChange={event => change('sortOrder', Number(event.target.value))} required /></div><label className="model-check" htmlFor="model-enabled"><input id="model-enabled" type="checkbox" checked={draft.enabled} onChange={event => change('enabled', event.target.checked)} />启用模型，允许用户选择</label></div>
      </fieldset>
      {error && <p className="admin-error" role="alert">{error}</p>}
      <div className="modal-actions"><button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>取消</button><button className="button button-primary" disabled={busy}>{busy ? '保存中…' : '保存配置'}</button></div>
    </form>
  </Modal>
}
