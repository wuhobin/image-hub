import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {Link, useLocation, useNavigate, useSearchParams} from 'react-router-dom'
import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {MagnifyingGlass} from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import {api} from '../lib/api'
import type {Page} from '../lib/types'
import {composePrompt, examplePrompt} from '../lib/templates'
import type {CreationTemplate, TemplateDraft, TemplateTerm} from '../lib/templates'
import {Modal} from '../components/Modal'
import './templates.css'

/** 公开模板库直接读取上架内容，筛选保留在 URL 中。 */
export default function Templates() {
    const [params, setParams] = useSearchParams()
    const [terms, setTerms] = useState<TemplateTerm[]>([])
    const [data, setData] = useState<Page<CreationTemplate> | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState('')
    const [revision, setRevision] = useState(0)
    const category = params.get('category') || ''
    const tag = params.get('tag') || ''
    const search = params.get('search') || ''
    const requestedPage = Number(params.get('page') || 1)
    const page = Number.isSafeInteger(requestedPage) && requestedPage > 0 ? requestedPage : 1
    const selected = params.get('template')
    const [searchInput, setSearchInput] = useState(search)
    const location = useLocation()
    useEffect(() => {
        setSearchInput(search)
    }, [search])
    useEffect(() => {
        const refresh = () => setRevision(value => value + 1)
        window.addEventListener('focus', refresh)
        return () => window.removeEventListener('focus', refresh)
    }, [])
    useEffect(() => {
        const controller = new AbortController()
        setLoading(true);
        setError('')
        const query = new URLSearchParams({page: String(page), pageSize: '12', search})
        if (category) query.set('categoryId', category)
        if (tag) query.set('tagId', tag)
        void Promise.all([
            api<Page<CreationTemplate>>('/public/templates?' + query, {signal: controller.signal, cache: 'no-store'}),
            api<TemplateTerm[]>('/public/templates/terms', {signal: controller.signal, cache: 'no-store'})
        ]).then(([result, terms]) => {
            if (!controller.signal.aborted) {
                setData(result);
                setTerms(terms)
            }
        })
            .catch(error => {
                if (!controller.signal.aborted) {
                    setData(null);
                    setError(error instanceof Error ? error.message : '模板读取失败')
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [category, tag, search, page, revision])

    function filter(key: string, value: string) {
        setParams(previous => {
            const next = new URLSearchParams(previous);
            next.delete('page');
            next.delete('template')
            if (value) next.set(key, value); else next.delete(key)
            return next
        })
    }

    return <main id="main" className="template-page">
        <header className="template-heading">
            <div><span className="section-kicker">从一个用途开始</span><h1>这次，你想创作什么？</h1>
                <p>选一个模板，填入你的内容。让第一张图片，从这里开始。</p></div>
            <Link className="quiet-link" to="/">自由创作 <ArrowUpRight size={17}/></Link>
        </header>
        <div className="template-filters">
            <nav className="template-categories" aria-label="模板分类">
                <button type="button" aria-pressed={!category} onClick={() => filter('category', '')}>全部模板</button>
                {terms.filter(term => term.kind === 'CATEGORY').map(term => <button type="button" key={term.id}
                                                                                    aria-pressed={category === String(term.id)}
                                                                                    onClick={() => filter('category', String(term.id))}>{term.name}</button>)}
            </nav>
            <form className="template-search" onSubmit={event => {
                event.preventDefault();
                filter('search', searchInput.trim())
            }}>
                <label className="visually-hidden" htmlFor="template-search">搜索模板</label>
                <MagnifyingGlass size={18} aria-hidden="true"/>
                <input id="template-search" value={searchInput} maxLength={100} placeholder="搜索用途、风格或标签"
                       onChange={event => setSearchInput(event.target.value)}/>
                <button className="quiet-link" type="submit">搜索</button>
            </form>
        </div>
        <div className="template-subfilters">
            <label>标签 <select className="format-select" aria-label="按标签筛选" value={tag}
                                onChange={event => filter('tag', event.target.value)}>
                <option value="">全部标签</option>
                {terms.filter(term => term.kind === 'TAG').map(term => <option key={term.id}
                                                                               value={term.id}>{term.name}</option>)}
            </select></label>
            {(category || tag || search) &&
                <button className="quiet-link" onClick={() => setParams({})}>清除筛选</button>}
            <span role="status">{loading ? '正在读取模板…' : data ? data.total + ' 个模板' : ''}</span>
        </div>
        {error ? <div className="template-state" role="alert"><p>{error}</p>
                <button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>重新加载
                </button>
            </div>
            : !loading && !data?.records.length ?
                <div className="template-state"><h2>暂时没有匹配的模板</h2><p>换个关键词，或查看全部模板。</p>
                    <button className="button button-secondary" onClick={() => setParams({})}>查看全部</button>
                </div>
                : <div className="template-grid" aria-busy={loading}>{data?.records.map(item => <Link
                    className="template-card" key={item.id}
                    to={{
                        pathname: '/templates',
                        search: '?' + new URLSearchParams({...Object.fromEntries(params), template: String(item.id)})
                    }}>
                    {item.exampleUrl ?
                        <div className="template-art"><img src={item.exampleUrl} alt={item.title + '示例效果'}
                                                           loading="lazy"/></div>
                        : <div className="template-text-art"><span>提示词示例</span><p>{examplePrompt(item)}</p></div>}
                    <div className="template-card-copy"><span
                        className="template-category-label">{item.category.name}</span>
                        <h2>{item.title}<ArrowUpRight size={19} aria-hidden="true"/></h2><p>{item.description}</p>
                        <div className="template-card-tags">{item.tags.map(tag => <span
                            key={tag.id}>{tag.name}</span>)}</div>
                        <small>{item.fields.length ? item.fields.length + ' 项填写 · 可修改提示词' : '直接使用 · 可修改提示词'}</small>
                    </div>
                </Link>)}</div>}
        {data && data.pages > 1 && <nav className="template-pagination" aria-label="模板分页">
            <button className="button button-secondary" disabled={loading || page <= 1}
                    onClick={() => setParams({...Object.fromEntries(params), page: String(page - 1)})}>上一页
            </button>
            <span>{page} / {data.pages}</span>
            <button className="button button-secondary" disabled={loading || page >= data.pages}
                    onClick={() => setParams({...Object.fromEntries(params), page: String(page + 1)})}>下一页
            </button>
        </nav>}
        {selected && <TemplateDialog key={selected} id={selected} draft={location.state?.templateDraft}
                                     onClose={() => setParams(previous => {
                                         const next = new URLSearchParams(previous);
                                         next.delete('template');
                                         return next
                                     })}/>}
    </main>
}

/** 填写不触发生图；手工编辑后保留原稿，重新组合由用户主动确认。 */
function TemplateDialog({id, draft, onClose}: { id: string; draft?: TemplateDraft; onClose: () => void }) {
    const navigate = useNavigate()
    const [item, setItem] = useState<CreationTemplate | null>(null)
    const [values, setValues] = useState<Record<string, string>>({})
    const [prompt, setPrompt] = useState('')
    const [manual, setManual] = useState(false)
    const [changed, setChanged] = useState(false)
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)
    const [revision, setRevision] = useState(0)
    useEffect(() => {
        const controller = new AbortController()
        setError('')
        void api<CreationTemplate>('/public/templates/' + encodeURIComponent(id), {
            signal: controller.signal,
            cache: 'no-store'
        })
            .then(item => {
                if (controller.signal.aborted) return
                const saved = draft?.id === item.id ? draft : undefined
                const initial = Object.fromEntries(item.fields.map(field => [field.key, saved?.values[field.key] ?? field.example]))
                setItem(item);
                setValues(initial);
                setPrompt(saved?.prompt ?? (item.fields.length ? composePrompt(item.promptPattern, initial) : item.promptPattern));
                setManual(saved?.manual ?? false)
            }).catch(error => {
                if (!controller.signal.aborted) setError(error instanceof Error ? error.message : '模板读取失败')
            })
        return () => controller.abort()
    }, [id, revision])

    function changeField(key: string, value: string) {
        const next = {...values, [key]: value}
        setValues(next)
        if (manual) setChanged(true)
        else if (item) setPrompt(composePrompt(item.promptPattern, next))
    }

    async function apply(event: FormEvent) {
        event.preventDefault()
        if (!item || busy) return
        if (!prompt.trim() || prompt.length > 4000) {
            setError('提示词需要 1–4000 个字符，请缩短填写内容或编辑提示词');
            return
        }
        setBusy(true);
        setError('')
        try {
            await api<CreationTemplate>('/public/templates/' + item.id, {cache: 'no-store'})
            const templateDraft: TemplateDraft = {id: item.id, title: item.title, values, prompt, manual}
            navigate('/', {state: {creationPrompt: prompt, templateDraft}})
        } catch (error) {
            setError(error instanceof Error ? error.message : '模板暂时不可用')
        } finally {
            setBusy(false)
        }
    }

    return <Modal title={item?.title || '正在读取模板'} className="template-dialog" onClose={busy ? () => {
    } : onClose}>
        {error && <p className="template-error" role="alert">{error}</p>}
        {!item ? error ? <button className="button button-secondary"
                                 onClick={() => setRevision(value => value + 1)}>重新加载</button> :
                <p role="status">正在准备填写项…</p>
            : <form onSubmit={apply} className="template-form">
                {item.exampleUrl &&
                    <img className="template-dialog-image" src={item.exampleUrl} alt={item.title + '示例效果'}/>}
                <p className="template-description">{item.description}</p>
                <div className="template-fields">{item.fields.map(field => <label key={field.key}
                                                                                  htmlFor={'template-field-' + field.key}>
                    <span>{field.label}{!field.required && <small>选填</small>}</span>
                    <textarea id={'template-field-' + field.key} value={values[field.key] || ''}
                              required={field.required} maxLength={500} rows={2}
                              placeholder={field.example} disabled={busy}
                              onChange={event => changeField(field.key, event.target.value)}/>
                </label>)}</div>
                {!item.fields.length &&
                    <p className="template-description">这个模板可直接使用，也可以展开修改提示词。</p>}
                {changed && <p className="template-notice"
                               role="status">填写项已改变，你手动修改的提示词仍被保留。需要更新时，请点击“重新组合”。</p>}
                <details className="template-prompt-editor">
                    <summary>查看 / 编辑完整提示词 <span>{prompt.length} / 4000</span></summary>
                    <label className="visually-hidden" htmlFor="template-final-prompt">完整提示词</label>
                    <textarea id="template-final-prompt" value={prompt} rows={8} maxLength={4000} disabled={busy}
                              onChange={event => {
                                  setPrompt(event.target.value);
                                  setManual(true)
                              }}/>
                    <button className="quiet-link" type="button" disabled={busy} onClick={() => {
                        if (manual && !window.confirm('重新组合会替换你手动修改的提示词，确定继续？')) return
                        setPrompt(item.fields.length ? composePrompt(item.promptPattern, values) : item.promptPattern);
                        setManual(false);
                        setChanged(false)
                    }}>按填写项重新组合
                    </button>
                </details>
                <p className="template-description">下一步可添加参考图、确认模型与画面参数。点击生成后才提交任务。</p>
                <button className="button button-primary" disabled={busy || !prompt.trim() || prompt.length > 4000}>
                    {busy ? '正在准备…' : '带入创作区'}<ArrowUpRight size={17}/></button>
            </form>}
    </Modal>
}
