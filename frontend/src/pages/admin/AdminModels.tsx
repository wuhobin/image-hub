import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'
import {Sparkle} from '@phosphor-icons/react/dist/csr/Sparkle'
import {PencilSimple} from '@phosphor-icons/react/dist/csr/PencilSimple'
import {ArrowClockwise} from '@phosphor-icons/react/dist/csr/ArrowClockwise'
import {Key} from '@phosphor-icons/react/dist/csr/Key'
import {Info} from '@phosphor-icons/react/dist/csr/Info'
import {CheckCircle} from '@phosphor-icons/react/dist/csr/CheckCircle'
import { adminApi } from '../../lib/admin/api'
import type { AiModelConfig, Page } from '../../lib/types'
import { Modal } from '../../components/Modal'
import {AdminSelect} from './AdminSelect'
import {ModelSizeFields} from './ModelSizeFields'
import {GENERATION_SIZES, generationSizesForModel, imageAspectRatio} from '../../lib/rules'
import './models.css'

type Draft = Omit<AiModelConfig, 'id' | 'keyConfigured' | 'updateTime'> & { apiKey: string }
const initial: Draft = {
    name: '',
    modelCode: 'gpt-image-2',
    baseUrl: 'https://api.openai.com',
    imagesPath: '/v1/images/generations',
    apiKey: '',
    sizes: [...GENERATION_SIZES],
    defaultSize: '1024x1024',
    qualities: ['low', 'medium', 'high', 'auto'],
    defaultQuality: 'medium',
    pointsCost: 1,
    sortOrder: 0,
    enabled: false
}
const qualityOptions = [{value: 'low', label: '低 · low'}, {value: 'medium', label: '中 · medium'}, {
    value: 'high',
    label: '高 · high'
}, {value: 'auto', label: '自动 · auto'}]
const message = (error: unknown) => error instanceof Error ? error.message : '请求失败，请稍后重试'

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
      <div className="admin-page-title">
          <div><span className="admin-eyebrow">AI 创作</span><h1>模型管理</h1>
              <p>连接生图服务，管理生成参数与单张消耗积分。</p></div>
          <button className="button button-primary" onClick={() => setEditing('new')}><Plus size={17}
                                                                                            aria-hidden="true"/>新增模型
          </button>
      </div>
      <p className="model-intro"><Info size={18} aria-hidden="true"/><span>支持 OpenAI Images 兼容接口。密钥仅用于服务端调用；停用或删除不影响已提交的任务。</span>
      </p>
      {saved && <p className="model-saved" role="status"><CheckCircle size={17} aria-hidden="true"/>{saved}</p>}
    {error && <p className="admin-error" role="alert">{error}<button className="quiet-link" onClick={() => setRevision(value => value + 1)}>重新加载</button></p>}
      <section aria-label="生图模型">
          <div className="models-heading"><h2>模型列表 <span>{!loading && data ? data.total : '—'}</span></h2>
              <button className="button button-small admin-refresh" aria-label="刷新模型列表" title="刷新模型列表"
                      disabled={loading} onClick={() => setRevision(value => value + 1)}><ArrowClockwise size={17}
                                                                                                         aria-hidden="true"/><span>刷新列表</span>
              </button>
          </div>
          <div className="models-list" aria-busy={loading}>
              {loading ? <div className="admin-table-state" role="status"><Sparkle size={28} aria-hidden="true"/>
                      <p>正在读取模型…</p></div>
                  : !data?.records.length ? <div className="admin-table-state"><Sparkle size={28} aria-hidden="true"/>
                          <h3>{error ? '模型暂时无法加载' : '添加你的第一个生图模型'}</h3>
                          <p>{error ? '请重新加载，或稍后再试。' : '连接服务并配置参数后，用户即可在 AI 创作中选择。'}</p>{!error &&
                              <button className="button button-secondary" onClick={() => setEditing('new')}><Plus size={16}
                                                                                                                  aria-hidden="true"/>新增模型
                              </button>}</div>
                      : data.records.map(model => <article className="model-row" key={model.id}>
                          <div className="model-row-heading"><span className="model-icon" aria-hidden="true"><Sparkle
                              size={24} weight="duotone"/></span>
                              <div className="model-row-main"><h2>{model.name}</h2><code>{model.modelCode}</code></div>
                              <span
                                  className={'admin-status' + (model.enabled ? ' is-positive' : '')}>{model.enabled ? '已启用' : '已停用'}</span>
                          </div>
                          <dl className="model-row-options">
                              <div>
                                  <dt>单张消耗</dt>
                                  <dd><strong>{model.pointsCost}</strong> 积分</dd>
                              </div>
                              <div>
                                  <dt>生成选项</dt>
                                  <dd>{model.sizes.length} 种尺寸 <span>·</span> {model.qualities.length} 种质量</dd>
                              </div>
                              <div>
                                  <dt>默认参数</dt>
                                  <dd>{imageAspectRatio(model.defaultSize)}
                                      <span>·</span> {model.defaultQuality}<small>{model.defaultSize}</small></dd>
                              </div>
                          </dl>
                          <div className="model-endpoint"><span>服务地址</span><code
                              title={model.baseUrl}>{model.baseUrl}</code></div>
                          <div className="model-row-footer"><span
                              className={'model-key-state' + (model.keyConfigured ? '' : ' is-missing')}><Key size={14}
                                                                                                              aria-hidden="true"/>{model.keyConfigured ? '密钥已配置' : '尚未配置密钥'}</span>
                              <div className="model-row-actions">
                                  <button className="quiet-link admin-danger-link" onClick={() => {
                                      setError('');
                                      setDeleting(model)
                                  }}>删除
                                  </button>
                                  <button className="button button-secondary button-small"
                                          onClick={() => setEditing(model)}><PencilSimple size={15} aria-hidden="true"/>编辑
                                  </button>
                              </div>
                          </div>
                      </article>)}
          </div>
          {data && data.pages > 1 &&
              <div className="admin-pagination model-pagination"><span>共 {data.total} 个模型</span>
                  <div>
                      <button className="button button-secondary button-small" disabled={loading || page <= 1}
                              onClick={() => setPage(value => value - 1)}>上一页
                      </button>
                      <span>{page} / {data.pages}</span>
                      <button className="button button-secondary button-small" disabled={loading || page >= data.pages}
                              onClick={() => setPage(value => value + 1)}>下一页
                      </button>
                  </div>
              </div>}
      </section>
    {editing && <ModelEditor model={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); setSaved('模型配置已保存'); setRevision(value => value + 1) }} />}
    {deleting && <Modal title={'删除“' + deleting.name + '”？'} onClose={() => { if (!busy) setDeleting(null) }}>
        <p className="admin-modal-copy">删除后无法用于新的创作，已提交的任务和历史记录会保留。此名称不能再次使用。</p>
      {error && <p role="alert" className="admin-error">{error}</p>}
      <div className="modal-actions"><button className="button button-secondary" disabled={busy} onClick={() => setDeleting(null)}>取消</button><button className="button button-danger" disabled={busy} onClick={() => void remove()}>{busy ? '删除中…' : '确认删除'}</button></div>
    </Modal>}
  </div>
}

function ModelEditor({ model, onClose, onSaved }: { model: AiModelConfig | 'new'; onClose: () => void; onSaved: () => void }) {
    const [draft, setDraft] = useState<Draft>(() => model === 'new' ? {...initial} : {...model, apiKey: ''})
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const lock = useRef(false)
    const presetSizes = generationSizesForModel(draft.modelCode)

  function change<K extends keyof Draft>(key: K, value: Draft[K]) { setDraft(previous => ({ ...previous, [key]: value })) }

    function changeModelCode(modelCode: string) {
        const sizes = generationSizesForModel(modelCode)
        setDraft(previous => ({
            ...previous, modelCode, ...(model === 'new' && sizes !== presetSizes
                ? {sizes: [...sizes], defaultSize: '1024x1024'} : {})
        }))
    }

    // 取消默认项时同步选择剩余第一项，避免保存不可用的默认参数。
    function changeAllowed(key: 'sizes' | 'qualities', values: string[]) {
        const defaultKey = key === 'sizes' ? 'defaultSize' : 'defaultQuality'
        setDraft(previous => ({
            ...previous,
            [key]: values,
            [defaultKey]: values.includes(previous[defaultKey]) ? previous[defaultKey] : values[0] || ''
        }))
        setError('')
    }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (lock.current) return
      const {sizes, qualities} = draft
      if (!sizes.length || !qualities.length) {
          setError('请至少选择一种允许的尺寸和质量');
          return
      }
      if (sizes.length > 64) {
          setError('允许的尺寸最多选择 64 项，请取消部分选项');
          return
      }
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
        <div className="model-form-scroll" tabIndex={0} role="region" aria-label="模型配置表单">
      <fieldset disabled={busy}>
          <section className="model-form-section" aria-labelledby="model-connection-title">
              <div className="model-section-heading"><span aria-hidden="true">01</span>
                  <div><h3 id="model-connection-title">接口连接</h3><p>设置模型名称、服务地址与调用凭证。</p></div>
              </div>
              <div className="model-form-grid">
                  <div><label htmlFor="model-name">显示名称</label><input id="model-name" value={draft.name}
                                                                          onChange={event => change('name', event.target.value)}
                                                                          required maxLength={80}
                                                                          placeholder="例如 GPT-Image-2"/></div>
                  <div><label htmlFor="model-code">模型代码</label><input id="model-code" value={draft.modelCode}
                                                                          onChange={event => changeModelCode(event.target.value)}
                                                                          required maxLength={120}/></div>
              </div>
              <label htmlFor="model-url">服务地址（Base URL）</label><input id="model-url" type="url"
                                                                          value={draft.baseUrl}
                                                                          onChange={event => change('baseUrl', event.target.value)}
                                                                          required maxLength={500}
                                                                          placeholder="https://api.openai.com"/>
              <label htmlFor="model-path">生图接口路径</label><input id="model-path" value={draft.imagesPath}
                                                                     onChange={event => change('imagesPath', event.target.value)}
                                                                     required maxLength={200}
                                                                     aria-describedby="model-endpoint-help"/>
              <p id="model-endpoint-help" className="model-help">服务地址与路径共同组成调用地址，例如
                  https://api.openai.com/v1/images/generations。</p>
              <label htmlFor="model-key">API Key {model !== 'new' && model.keyConfigured &&
                  <span>· 已配置，留空保留</span>}</label><input id="model-key" type="password"
                                                                autoComplete="new-password" value={draft.apiKey}
                                                                onChange={event => change('apiKey', event.target.value)}
                                                                maxLength={512}
                                                                placeholder={model !== 'new' && model.keyConfigured ? '留空保留原密钥' : '填写供应商密钥'}/>
          </section>
          <section className="model-form-section" aria-labelledby="model-generation-title">
              <div className="model-section-heading"><span aria-hidden="true">02</span>
                  <div><h3 id="model-generation-title">生成参数</h3><p>定义用户可以选择的尺寸、质量及默认值。</p></div>
              </div>
              <ModelSizeFields modelCode={draft.modelCode} sizes={draft.sizes} defaultSize={draft.defaultSize}
                               existingSizes={model === 'new' ? [] : model.sizes} disabled={busy}
                               onChange={values => changeAllowed('sizes', values)}
                               onDefaultChange={value => change('defaultSize', value)}/>
              <div className="model-form-grid">
                  <div><label htmlFor="model-qualities">允许的质量</label><AdminSelect id="model-qualities"
                                                                                       label="允许的质量" multiple
                                                                                       value={draft.qualities}
                                                                                       options={qualityOptions}
                                                                                       disabled={busy}
                                                                                       onChange={values => changeAllowed('qualities', values)}/>
                  </div>
                  <div><label htmlFor="model-default-quality">默认质量</label><AdminSelect id="model-default-quality"
                                                                                           label="默认质量"
                                                                                           value={draft.defaultQuality}
                                                                                           options={qualityOptions.filter(option => draft.qualities.includes(option.value))}
                                                                                           disabled={busy}
                                                                                           onChange={value => change('defaultQuality', value)}/>
                  </div>
              </div>
              <details className="model-field-guide">
                  <summary>尺寸与质量选择说明</summary>
                  <p>先选画面比例，再勾选该比例开放的分辨率。各比例独立配置，切换比例保留已选档位。“使用全部预设”会替换所有已选尺寸；最多允许
                      64 种尺寸。</p>
                  <p>默认比例和分辨率只显示已开放选项。取消当前默认项时，会自动选择剩余第一项。质量请按供应商实际支持的选项配置。</p>
              </details>
          </section>
          <section className="model-form-section" aria-labelledby="model-availability-title">
              <div className="model-section-heading"><span aria-hidden="true">03</span>
                  <div><h3 id="model-availability-title">积分与可用性</h3><p>控制新任务的消耗与模型在用户端的展示。</p>
                  </div>
              </div>
              <div className="model-form-grid">
                  <div><label htmlFor="model-points-cost">单张消耗积分</label><input id="model-points-cost"
                                                                                     type="number" min={1}
                                                                                     max={2147483647} step={1}
                                                                                     value={draft.pointsCost}
                                                                                     onChange={event => change('pointsCost', Number(event.target.value))}
                                                                                     required
                                                                                     aria-describedby="model-points-help"/>
                  </div>
                  <div><label htmlFor="model-sort">排序（数字越小越靠前）</label><input id="model-sort" type="number"
                                                                                      min={0} max={9999} step={1}
                                                                                      value={draft.sortOrder}
                                                                                      onChange={event => change('sortOrder', Number(event.target.value))}
                                                                                      required/></div>
              </div>
              <p id="model-points-help" className="model-help">积分填写正整数，默认 1
                  积分。提交时预留，生成并保存成功后扣除，失败释放。修改仅影响新提交的任务。</p>
              <label className="model-check" htmlFor="model-enabled"><input id="model-enabled" type="checkbox"
                                                                            checked={draft.enabled}
                                                                            onChange={event => change('enabled', event.target.checked)}/><span>启用模型<small>启用后，用户可以在 AI 创作中选择此模型。</small></span></label>
          </section>
      </fieldset>
        </div>
        <div className="model-editor-footer">
            {error && <p className="admin-error" role="alert">{error}</p>}
            <div className="modal-actions"><span className="model-footer-note">保存后应用于新的创作</span>
                <button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>取消
                </button>
                <button className="button button-primary" disabled={busy}>{busy ? '保存中…' : '保存配置'}</button>
            </div>
        </div>
    </form>
  </Modal>
}
