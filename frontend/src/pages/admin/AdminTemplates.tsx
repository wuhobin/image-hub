import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {adminApi} from '../../lib/admin/api'
import {api} from '../../lib/api'
import {fileError} from '../../lib/rules'
import type {Page, SharedCreation} from '../../lib/types'
import {examplePrompt, templateInput} from '../../lib/templates'
import type {CreationTemplate, TemplateInput, TemplateTerm} from '../../lib/templates'
import {Modal} from '../../components/Modal'
import '../templates.css'

const message = (error: unknown) => error instanceof Error ? error.message : '操作失败，请稍后重试'

/** 管理员维护模板、分类及标签；导入与上传不触发生图。 */
export default function AdminTemplates() {
    const [terms, setTerms] = useState<TemplateTerm[]>([])
    const [data, setData] = useState<Page<CreationTemplate> | null>(null)
    const [search, setSearch] = useState('')
    const [query, setQuery] = useState('')
    const [category, setCategory] = useState('')
    const [tag, setTag] = useState('')
    const [enabled, setEnabled] = useState('')
    const [page, setPage] = useState(1)
    const [revision, setRevision] = useState(0)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [editing, setEditing] = useState<CreationTemplate | 'new' | null>(null)
    const [termManager, setTermManager] = useState(false)
    const [importing, setImporting] = useState(false)
    useEffect(() => {
        const controller = new AbortController()
        const params = new URLSearchParams({page: String(page), pageSize: '20', search: query})
        if (category) params.set('categoryId', category)
        if (tag) params.set('tagId', tag)
        if (enabled) params.set('enabled', enabled)
        setLoading(true);
        setError('')
        void Promise.all([
            adminApi<Page<CreationTemplate>>('/templates?' + params, {signal: controller.signal}),
            adminApi<TemplateTerm[]>('/templates/terms', {signal: controller.signal})
        ]).then(([data, terms]) => {
            if (!controller.signal.aborted) {
                setData(data);
                setTerms(terms)
            }
        })
            .catch(error => {
                if (!controller.signal.aborted) setError(message(error))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [query, category, tag, enabled, page, revision])
    const refresh = () => setRevision(value => value + 1)

    async function edit(id: number) {
        setError('')
        try {
            setEditing(await adminApi<CreationTemplate>('/templates/' + id))
        } catch (error) {
            setError(message(error))
        }
    }

    return <div className="template-admin">
        <div className="admin-page-title">
            <div><span className="admin-eyebrow">场景与提示词</span><h1>模板管理</h1>
                <p>让用户从具体用途出发，完成第一次创作。</p></div>
            <div className="template-admin-actions">
                <button className="button button-secondary" onClick={() => setTermManager(true)}>分类与标签</button>
                <button className="button button-secondary" onClick={() => setImporting(true)}
                        disabled={!terms.some(term => term.kind === 'CATEGORY')}>导入公开作品
                </button>
                <button className="button button-primary" onClick={() => setEditing('new')}>新建模板</button>
            </div>
        </div>
        <form className="template-admin-filters" onSubmit={event => {
            event.preventDefault();
            setQuery(search.trim());
            setPage(1)
        }}>
            <input aria-label="搜索模板名称或标签" maxLength={100} placeholder="搜索模板名称或标签" value={search}
                   onChange={event => setSearch(event.target.value)}/>
            <select className="format-select" aria-label="模板分类" value={category} onChange={event => {
                setCategory(event.target.value);
                setPage(1)
            }}>
                <option value="">全部分类</option>
                {terms.filter(term => term.kind === 'CATEGORY').map(term => <option key={term.id}
                                                                                    value={term.id}>{term.name}</option>)}
            </select>
            <select className="format-select" aria-label="模板标签" value={tag} onChange={event => {
                setTag(event.target.value);
                setPage(1)
            }}>
                <option value="">全部标签</option>
                {terms.filter(term => term.kind === 'TAG').map(term => <option key={term.id}
                                                                               value={term.id}>{term.name}</option>)}
            </select>
            <select className="format-select" aria-label="上架状态" value={enabled} onChange={event => {
                setEnabled(event.target.value);
                setPage(1)
            }}>
                <option value="">全部状态</option>
                <option value="true">已上架</option>
                <option value="false">草稿 / 已下架</option>
            </select>
            <button className="button button-secondary" type="submit">查询</button>
        </form>
        {error && <p className="template-error" role="alert">{error}
            <button className="quiet-link" onClick={refresh}>重试</button>
        </p>}
        <div className="template-admin-list" aria-busy={loading}>
            {!data && loading ? <p role="status">正在读取模板…</p> : data?.records.map(item => <article
                className="template-admin-row" key={item.id}>
                {item.exampleUrl ? <img src={item.exampleUrl} alt="" loading="lazy"/> :
                    <div className="template-admin-text">文字<br/>模板</div>}
                <div className="template-admin-row-main"><h2>{item.title}</h2>
                    <p>{item.category.name} · {item.fields.length} 个填写项 · 排序 {item.sortOrder}</p>
                    <div className="template-card-tags">{item.tags.map(tag => <span
                        key={tag.id}>{tag.name}</span>)}</div>
                </div>
                <span
                    className={'template-state-label' + (item.enabled ? ' is-published' : '')}>{item.enabled ? '已上架' : '草稿 / 已下架'}</span>
                <button className="button button-secondary button-small" onClick={() => void edit(item.id)}>编辑
                </button>
            </article>)}
            {!loading && data && !data.records.length &&
                <p className="template-description">没有匹配的模板，可以调整筛选或新建模板。</p>}
        </div>
        {data && data.pages > 1 && <nav className="template-pagination" aria-label="模板管理分页">
            <button className="button button-secondary" disabled={loading || page <= 1}
                    onClick={() => setPage(page - 1)}>上一页
            </button>
            <span>{page} / {data.pages}</span>
            <button className="button button-secondary" disabled={loading || page >= data.pages}
                    onClick={() => setPage(page + 1)}>下一页
            </button>
        </nav>}
        {editing && <TemplateEditor item={editing === 'new' ? undefined : editing} terms={terms}
                                    onTerms={setTerms} onClose={() => setEditing(null)} onSaved={() => {
            setEditing(null);
            refresh()
        }}/>}
        {termManager && <TermManager terms={terms} onTerms={setTerms} onClose={() => {
            setTermManager(false);
            refresh()
        }}/>}
        {importing && <ImportPicker terms={terms} onClose={() => setImporting(false)}
                                    onImported={item => {
                                        setImporting(false);
                                        setEditing(item);
                                        refresh()
                                    }}/>}
    </div>
}

/** 内容先保存，图片上传失败时明确保留已保存编号，重试不会创建重复模板。 */
function TemplateEditor({item, terms, onTerms, onClose, onSaved}: {
    item?: CreationTemplate;
    terms: TemplateTerm[];
    onTerms: (terms: TemplateTerm[]) => void;
    onClose: () => void;
    onSaved: () => void
}) {
    const [saved, setSaved] = useState(item)
    const [value, setValue] = useState<TemplateInput>(() => item ? templateInput(item) : {
        title: '', description: '', categoryId: terms.find(term => term.kind === 'CATEGORY')?.id || 0, tagIds: [],
        promptPattern: '创作一张关于{{subject}}的图片，采用{{style}}风格。', fields: [
            {key: 'subject', label: '画面主体', example: '清晨的海边书店', required: true},
            {key: 'style', label: '画面风格', example: '温暖的胶片摄影', required: true}
        ], enabled: false, sortOrder: 0
    })
    const [file, setFile] = useState<File | null>(null)
    const [removeImage, setRemoveImage] = useState(false)
    const [newTag, setNewTag] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')

    function change<K extends keyof TemplateInput>(key: K, next: TemplateInput[K]) {
        setValue(previous => ({...previous, [key]: next}))
    }

    async function addTag() {
        if (!newTag.trim() || busy) return
        const existing = terms.find(term => term.kind === 'TAG' && term.name.toLocaleLowerCase() === newTag.trim().toLocaleLowerCase())
        if (existing) {
            change('tagIds', [...new Set([...value.tagIds, existing.id])]);
            setNewTag('');
            return
        }
        setBusy(true);
        setError('')
        try {
            const term = await adminApi<TemplateTerm>('/templates/terms', {
                method: 'POST',
                body: JSON.stringify({kind: 'TAG', name: newTag.trim(), sortOrder: 0})
            })
            onTerms([...terms, term]);
            change('tagIds', [...value.tagIds, term.id]);
            setNewTag('')
        } catch (error) {
            setError(message(error))
        } finally {
            setBusy(false)
        }
    }

    async function save(event: FormEvent) {
        event.preventDefault()
        if (busy) return
        setBusy(true);
        setError('')
        let contentSaved = false
        try {
            const result = await adminApi<CreationTemplate>('/templates' + (saved ? '/' + saved.id : ''), {
                method: saved ? 'PUT' : 'POST', body: JSON.stringify(value)
            })
            setSaved(result);
            contentSaved = true
            if (file) {
                const body = new FormData();
                body.append('file', file)
                await adminApi('/templates/' + result.id + '/image', {method: 'POST', body})
            } else if (removeImage) await adminApi('/templates/' + result.id + '/image', {method: 'DELETE'})
            onSaved()
        } catch (error) {
            setError((contentSaved ? '文字内容已保存，示例图更新失败：' : '') + message(error))
        } finally {
            setBusy(false)
        }
    }

    return <Modal title={saved ? '编辑模板' : '新建模板'} onClose={busy ? () => {
    } : onClose} className="template-dialog template-admin template-editor">
        <form className="template-form" onSubmit={save}>
            <div className="template-fields">
                <label>模板名称<input required maxLength={80} value={value.title}
                                      onChange={event => change('title', event.target.value)} disabled={busy}/></label>
                <label>所属分类<select className="format-select" required value={value.categoryId || ''}
                                       onChange={event => change('categoryId', Number(event.target.value))}
                                       disabled={busy}>
                    <option value="" disabled>请选择分类</option>
                    {terms.filter(term => term.kind === 'CATEGORY').map(term => <option key={term.id}
                                                                                        value={term.id}>{term.name}</option>)}
                </select></label>
            </div>
            <label>用途说明<textarea maxLength={300} rows={2} value={value.description}
                                     onChange={event => change('description', event.target.value)}
                                     disabled={busy}/></label>
            <fieldset className="template-tag-picker" disabled={busy}>
                <legend>标签（最多 10 个）</legend>
                {terms.filter(term => term.kind === 'TAG').map(term => <label key={term.id}><input type="checkbox"
                                                                                                   checked={value.tagIds.includes(term.id)}
                                                                                                   disabled={!value.tagIds.includes(term.id) && value.tagIds.length >= 10}
                                                                                                   onChange={event => change('tagIds', event.target.checked ? [...value.tagIds, term.id] : value.tagIds.filter(id => id !== term.id))}/>{term.name}
                </label>)}
                <div className="template-inline-create"><input aria-label="新标签名称"
                                                               placeholder="新建标签，或输入已有标签名" maxLength={30}
                                                               value={newTag}
                                                               onChange={event => setNewTag(event.target.value)}/>
                    <button type="button" className="button button-secondary button-small"
                            disabled={!newTag.trim() || value.tagIds.length >= 10} onClick={() => void addTag()}>添加标签
                    </button>
                </div>
            </fieldset>
            <section className="template-field-config"><h3>用户填写项</h3><p
                className="template-description">字段标识用于提示词，例如 subject 对应 {'{{subject}}'}。建议保留 2–4
                项。</p>
                {value.fields.map((field, index) => <div className="template-field-row" key={index}>
                    <label>字段标识<input required pattern="[a-z][a-z0-9_]{0,31}" maxLength={32} value={field.key}
                                          disabled={busy}
                                          onChange={event => change('fields', value.fields.map((item, i) => i === index ? {
                                              ...item,
                                              key: event.target.value
                                          } : item))}/></label>
                    <label>显示名称<input required maxLength={40} value={field.label} disabled={busy}
                                          onChange={event => change('fields', value.fields.map((item, i) => i === index ? {
                                              ...item,
                                              label: event.target.value
                                          } : item))}/></label>
                    <label className="template-field-example">示例内容<input maxLength={300} value={field.example}
                                                                             disabled={busy}
                                                                             onChange={event => change('fields', value.fields.map((item, i) => i === index ? {
                                                                                 ...item,
                                                                                 example: event.target.value
                                                                             } : item))}/></label>
                    <label className="template-check"><input type="checkbox" checked={field.required} disabled={busy}
                                                             onChange={event => change('fields', value.fields.map((item, i) => i === index ? {
                                                                 ...item,
                                                                 required: event.target.checked
                                                             } : item))}/>必填</label>
                    <button className="quiet-link" type="button" disabled={busy}
                            onClick={() => change('fields', value.fields.filter((_, i) => i !== index))}>移除
                    </button>
                </div>)}
                <button type="button" className="button button-secondary button-small"
                        disabled={busy || value.fields.length >= 8}
                        onClick={() => change('fields', [...value.fields, {
                            key: '',
                            label: '',
                            example: '',
                            required: true
                        }])}>添加填写项
                </button>
            </section>
            <label>提示词模板<textarea required maxLength={4000} rows={6} value={value.promptPattern}
                                       onChange={event => change('promptPattern', event.target.value)} disabled={busy}/></label>
            <details className="template-admin-preview">
                <summary>查看填写示例后的提示词</summary>
                <p>{examplePrompt(value)}</p></details>
            <div><label>示例图片（选填，最大 10 MB）<input type="file" accept="image/jpeg,image/png,image/webp,image/gif"
                                                        disabled={busy}
                                                        onChange={event => {
                                                            const selected = event.target.files?.[0]
                                                            if (selected) {
                                                                const invalid = fileError(selected);
                                                                if (invalid) {
                                                                    setError(invalid);
                                                                    event.target.value = '';
                                                                    return
                                                                }
                                                            }
                                                            setFile(selected || null);
                                                            setRemoveImage(false);
                                                            setError('')
                                                        }}/></label>
                {saved?.exampleUrl && !removeImage &&
                    <div className="template-existing-image"><img src={saved.exampleUrl} alt="当前示例图"/>
                        <button type="button" className="quiet-link" disabled={busy} onClick={() => {
                            setRemoveImage(true);
                            setFile(null)
                        }}>移除现有图片
                        </button>
                    </div>}
                {removeImage && <p className="template-description">保存后移除示例图，模板保留文字内容。
                    <button type="button" className="quiet-link" onClick={() => setRemoveImage(false)}>撤销</button>
                </p>}
            </div>
            {saved?.sourceAuthor &&
                <p className="template-description">导入来源：{saved.sourceAuthor} · {saved.sourceShareId}。图片与提示词已独立保存。</p>}
            <div className="template-fields"><label>排序（小值在前）<input type="number" min={0} max={100000} required
                                                                         value={value.sortOrder}
                                                                         onChange={event => change('sortOrder', Number(event.target.value))}
                                                                         disabled={busy}/></label>
                <label className="template-check"><input type="checkbox" checked={value.enabled} disabled={busy}
                                                         onChange={event => change('enabled', event.target.checked)}/>上架后对所有访客可见</label>
            </div>
            {error && <p className="template-error" role="alert">{error}</p>}
            <div className="template-admin-actions">
                <button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>取消
                </button>
                <button className="button button-primary"
                        disabled={busy || !value.categoryId}>{busy ? '正在保存…' : '保存模板'}</button>
            </div>
        </form>
    </Modal>
}

/** 词条可排序、改名；引用中的词条由服务端拒绝删除。 */
function TermManager({terms, onTerms, onClose}: {
    terms: TemplateTerm[];
    onTerms: (terms: TemplateTerm[]) => void;
    onClose: () => void
}) {
    const [kind, setKind] = useState<'CATEGORY' | 'TAG'>('CATEGORY')
    const [editing, setEditing] = useState<number | null>(null)
    const [name, setName] = useState('')
    const [sortOrder, setSortOrder] = useState(0)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')

    async function run(action: () => Promise<unknown>) {
        setBusy(true);
        setError('')
        try {
            await action();
            onTerms(await adminApi<TemplateTerm[]>('/templates/terms'));
            setEditing(null);
            setName('');
            setSortOrder(0)
        } catch (error) {
            setError(message(error))
        } finally {
            setBusy(false)
        }
    }

    return <Modal title="分类与标签" onClose={busy ? () => {
    } : onClose} className="template-dialog template-admin">
        <div className="template-categories">
            <button aria-pressed={kind === 'CATEGORY'} disabled={busy} onClick={() => {
                setKind('CATEGORY');
                setEditing(null);
                setName('')
            }}>分类
            </button>
            <button aria-pressed={kind === 'TAG'} disabled={busy} onClick={() => {
                setKind('TAG');
                setEditing(null);
                setName('')
            }}>标签
            </button>
        </div>
        <form className="template-term-form" onSubmit={event => {
            event.preventDefault()
            void run(() => adminApi('/templates/terms' + (editing ? '/' + editing : ''), {
                method: editing ? 'PUT' : 'POST',
                body: JSON.stringify({kind, name: name.trim(), sortOrder})
            }))
        }}>
            <label>名称<input required maxLength={30} value={name} disabled={busy}
                              onChange={event => setName(event.target.value)}/></label>
            <label>排序<input type="number" required min={0} max={100000} value={sortOrder} disabled={busy}
                              onChange={event => setSortOrder(Number(event.target.value))}/></label>
            <button className="button button-primary" disabled={busy}>{editing ? '保存修改' : '新增'}</button>
            {editing && <button type="button" className="quiet-link" disabled={busy} onClick={() => {
                setEditing(null);
                setName('');
                setSortOrder(0)
            }}>取消修改</button>}
        </form>
        {error && <p className="template-error" role="alert">{error}</p>}
        <div className="template-term-list">{terms.filter(term => term.kind === kind).map(term => <div key={term.id}>
            <span>{term.name}<small>排序 {term.sortOrder}</small></span>
            <button className="quiet-link" disabled={busy} onClick={() => {
                setEditing(term.id);
                setName(term.name);
                setSortOrder(term.sortOrder)
            }}>编辑
            </button>
            <button className="quiet-link" disabled={busy} onClick={() => {
                if (window.confirm('删除“' + term.name + '”？仍有模板使用时将无法删除。')) void run(() => adminApi('/templates/terms/' + term.id, {method: 'DELETE'}))
            }}>删除
            </button>
        </div>)}</div>
    </Modal>
}

/** 来源只展示公开作品；真正导入时服务端重新检查公开状态并复制原图。 */
function ImportPicker({terms, onClose, onImported}: {
    terms: TemplateTerm[];
    onClose: () => void;
    onImported: (item: CreationTemplate) => void
}) {
    const [data, setData] = useState<Page<SharedCreation> | null>(null)
    const [page, setPage] = useState(1)
    const [revision, setRevision] = useState(0)
    const [selected, setSelected] = useState<SharedCreation | null>(null)
    const [title, setTitle] = useState('')
    const [categoryId, setCategoryId] = useState(terms.find(term => term.kind === 'CATEGORY')?.id || 0)
    const [loading, setLoading] = useState(true)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState('')
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true);
        setError('');
        setSelected(null)
        void api<Page<SharedCreation>>('/public/creations?page=' + page + '&pageSize=12', {
            signal: controller.signal,
            cache: 'no-store'
        })
            .then(data => {
                if (!controller.signal.aborted) setData(data)
            })
            .catch(error => {
                if (!controller.signal.aborted) setError(message(error))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [page, revision])

    async function submit(event: FormEvent) {
        event.preventDefault()
        if (!selected || busy) return
        setBusy(true);
        setError('')
        try {
            onImported(await adminApi<CreationTemplate>('/templates/import', {
                method: 'POST',
                body: JSON.stringify({shareId: selected.shareId, title, categoryId})
            }))
        } catch (error) {
            setError(message(error))
        } finally {
            setBusy(false)
        }
    }

    return <Modal title="从公开作品导入" className="template-dialog template-admin" onClose={busy ? () => {
    } : onClose}>
        <p className="template-description">选择已公开提示词的作品，复制为独立草稿，再编辑填写项。不会再次生图或扣积分。</p>
        {error && <p className="template-error" role="alert">{error}
            <button className="quiet-link" disabled={busy} onClick={() => setRevision(value => value + 1)}>重新加载
            </button>
        </p>}
        {loading ? <p role="status">正在读取作品…</p> : <div
            className="template-import-grid">{data?.records.filter(work => work.promptPublic && work.prompt).map(work =>
            <button type="button"
                    key={work.shareId} disabled={busy} aria-pressed={selected?.shareId === work.shareId}
                    onClick={() => {
                        setSelected(work);
                        setTitle(work.prompt!.slice(0, 30))
                    }}>
                <img src={work.imageUrl} alt={work.authorName + '的作品'} loading="lazy"/><span>{work.prompt}</span>
            </button>)}</div>}
        {!loading && !data?.records.some(work => work.promptPublic && work.prompt) &&
            <p className="template-description">本页没有公开提示词的作品。可翻页查看，或手动新建模板。</p>}
        {data && data.pages > 1 && <div className="template-pagination">
            <button className="button button-secondary" disabled={busy || loading || page <= 1}
                    onClick={() => setPage(page - 1)}>上一页
            </button>
            <span>{page} / {data.pages}</span>
            <button className="button button-secondary" disabled={busy || loading || page >= data.pages}
                    onClick={() => setPage(page + 1)}>下一页
            </button>
        </div>}
        {selected && <form onSubmit={submit} className="template-form"><label>模板名称<input required maxLength={80}
                                                                                             value={title}
                                                                                             disabled={busy}
                                                                                             onChange={event => setTitle(event.target.value)}/></label>
            <label>所属分类<select className="format-select" value={categoryId} disabled={busy}
                                   onChange={event => setCategoryId(Number(event.target.value))}>
                {terms.filter(term => term.kind === 'CATEGORY').map(term => <option key={term.id}
                                                                                    value={term.id}>{term.name}</option>)}</select></label>
            <button className="button button-primary"
                    disabled={busy}>{busy ? '正在复制图片与提示词…' : '导入并编辑'}</button>
        </form>}
    </Modal>
}
