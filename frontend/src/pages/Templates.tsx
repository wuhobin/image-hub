import {useEffect, useState} from 'react'
import type {FormEvent} from 'react'
import {Link, useLocation, useNavigate, useSearchParams} from 'react-router-dom'
import {ArrowUpRight} from '@phosphor-icons/react/dist/csr/ArrowUpRight'
import {MagnifyingGlass} from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import {SquaresFour} from '@phosphor-icons/react/dist/csr/SquaresFour'
import {ArrowRight} from '@phosphor-icons/react/dist/csr/ArrowRight'
import {Pause} from '@phosphor-icons/react/dist/csr/Pause'
import {Play} from '@phosphor-icons/react/dist/csr/Play'
import {api} from '../lib/api'
import type {Page} from '../lib/types'
import {composePrompt, loadTemplateShowcase, templateCoverUrl} from '../lib/templates'
import type {CreationTemplate, TemplateDraft, TemplateTerm} from '../lib/templates'
import {Modal} from '../components/Modal'
import './templates.css'

/** 配置封面优先于上传示例及本地插画，缺图或加载失败时仍保留可用的模板入口。 */
function TemplateArtwork({item}: { item: CreationTemplate }) {
    const [failedSource, setFailedSource] = useState('')
    const source = templateCoverUrl(item)
    const imageKind = item.coverUrl ? '模板封面' : item.exampleUrl ? '示例效果' : '灵感封面'
    const showImage = source && failedSource !== source
    return <div className="template-library-artwork" data-category={item.category.name}
                data-example={!item.coverUrl && Boolean(item.exampleUrl)}>
        {showImage ? <img src={source} alt={item.title + imageKind} referrerPolicy="no-referrer"
                          loading="lazy" decoding="async" onError={() => setFailedSource(source)}/>
            : <div className="template-library-artwork-fallback" aria-hidden="true">
                <SquaresFour size={36} weight="light"/><span>{item.category.name}</span><strong>{item.title}</strong>
            </div>}
        <span className="template-library-image-kind">{showImage ? imageKind : '场景模板'}</span>
    </div>
}

/** 公开模板库直接读取上架内容，筛选保留在 URL 中。 */
export default function Templates() {
    const [params, setParams] = useSearchParams()
    const [heroPaused, setHeroPaused] = useState(false)
    const [showcase, setShowcase] = useState<CreationTemplate[]>([])
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
        const controller = new AbortController()
        void loadTemplateShowcase(controller.signal)
            .then(items => {
                if (!controller.signal.aborted) setShowcase(items)
            })
            .catch(() => { /* 装饰展示加载失败时隐藏，不影响模板筛选与使用。 */
            })
        return () => controller.abort()
    }, [])
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

    return <main id="main" className="template-library">
        <header className="template-library-hero">
            <div className="template-library-hero-copy">
                <span className="template-library-eyebrow"><SquaresFour size={15} aria-hidden="true"/> 模板库 <span>/ CREATIVE STARTERS</span></span>
                <h1>你的下一张作品，<br/><span>从这里开始。</span></h1>
                <p>选一个心动的场景，填入想法，开始创作。</p>
                <Link className="template-library-create" to="/">自由创作 <ArrowUpRight size={17}
                                                                                        aria-hidden="true"/></Link>
            </div>
            {showcase.length > 0 &&
                <div className="template-library-hero-gallery" data-paused={heroPaused} data-count={showcase.length}>
                    {showcase.map((item, index) => <div key={item.id}
                                                        className={'template-library-hero-print template-library-print-' + ['landscape', 'avatar', 'product'][index]}
                                                        aria-hidden="true">
                        <img src={templateCoverUrl(item)} alt="" referrerPolicy="no-referrer" decoding="async"
                             onError={() => setShowcase(current => current.filter(template => template.id !== item.id))}/>
                        <span>{item.title}</span>
                    </div>)}
                    <button className="template-library-hero-motion" type="button"
                            aria-label={heroPaused ? '开启封面悬浮动效' : '暂停封面悬浮动效'}
                            onClick={() => setHeroPaused(paused => !paused)}>
                        {heroPaused ? <Play size={13} weight="fill" aria-hidden="true"/> :
                            <Pause size={13} weight="fill" aria-hidden="true"/>}
                        <span>{heroPaused ? '开启动效' : '暂停动效'}</span>
                    </button>
                </div>}
        </header>
        <section className="template-library-collection" aria-label="可用模板">
            <div className="template-library-toolbar">
                <nav className="template-library-categories" aria-label="模板分类">
                    <button type="button" aria-pressed={!category} onClick={() => filter('category', '')}>全部模板
                    </button>
                    {terms.filter(term => term.kind === 'CATEGORY').map(term =>
                        <button type="button" key={term.id} aria-pressed={category === String(term.id)}
                                onClick={() => filter('category', String(term.id))}>{term.name}</button>)}
                </nav>
                <form className="template-library-search" role="search" onSubmit={event => {
                    event.preventDefault();
                    filter('search', searchInput.trim())
                }}>
                    <label className="visually-hidden" htmlFor="template-search">搜索模板</label>
                    <MagnifyingGlass size={18} aria-hidden="true"/>
                    <input id="template-search" value={searchInput} maxLength={100}
                           placeholder="搜索你想创作的场景" onChange={event => setSearchInput(event.target.value)}/>
                    <button type="submit" aria-label="搜索"><ArrowRight size={17} aria-hidden="true"/></button>
                </form>
            </div>
            <div className="template-library-results-heading">
                <div>
                    <h2>{search ? '搜索结果' : terms.find(term => String(term.id) === category)?.name || '探索全部模板'}</h2>
                    <span role="status">{loading ? '正在读取…' : data ? data.total + ' 个创作起点' : ''}</span></div>
                <div className="template-library-result-controls">
                    {(category || tag || search) &&
                        <button className="template-library-clear" onClick={() => setParams({})}>清除筛选</button>}
                    <label className="template-library-tag-filter"><span className="visually-hidden">标签</span>
                        <select className="format-select" aria-label="按标签筛选" value={tag}
                                onChange={event => filter('tag', event.target.value)}>
                            <option value="">全部标签</option>
                            {terms.filter(term => term.kind === 'TAG').map(term =>
                                <option key={term.id} value={term.id}>{term.name}</option>)}
                        </select>
                    </label>
                </div>
            </div>
            {error ? <div className="template-library-state" role="alert"><p>{error}</p>
                    <button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>重新加载
                    </button>
                </div>
                : !loading && !data?.records.length ? <div className="template-library-state">
                        <MagnifyingGlass size={32} weight="light" aria-hidden="true"/><h2>暂时没有匹配的模板</h2>
                        <p>换个关键词，或查看全部模板。</p>
                        <button className="button button-secondary" onClick={() => setParams({})}>查看全部</button>
                    </div>
                    : <div className="template-library-grid" aria-busy={loading}>
                        {loading && !data && <>
                            <p className="visually-hidden" role="status">正在准备场景模板…</p>
                            {Array.from({length: 6}, (_, index) => <div className="template-library-skeleton"
                                                                        key={index} aria-hidden="true">
                                <div/>
                                <span/><span/></div>)}
                        </>}
                        {data?.records.map(item => <Link className="template-library-card" key={item.id}
                                                         aria-label={'使用“' + item.title + '”模板'}
                                                         to={{
                                                             pathname: '/templates', search: '?' + new URLSearchParams({
                                                                 ...Object.fromEntries(params),
                                                                 template: String(item.id)
                                                             })
                                                         }}>
                            <div className="template-library-card-visual">
                                <TemplateArtwork item={item}/>
                                <span className="template-library-category-badge">{item.category.name}</span>
                                <span className="template-library-card-use">使用模板 <ArrowUpRight size={17}
                                                                                                   aria-hidden="true"/></span>
                            </div>
                            <div className="template-library-card-title"><h3>{item.title}</h3>
                                <ArrowUpRight size={19} aria-hidden="true"/></div>
                            <p className="template-library-card-description">{item.description}</p>
                            <div className="template-library-card-meta">
                                <span>{item.fields.length ? item.fields.length + ' 项填写' : '直接使用'}</span>
                                {item.tags.slice(0, 2).map(term => <span key={term.id}>{term.name}</span>)}
                            </div>
                        </Link>)}
                    </div>}
            {data && data.pages > 1 && <nav className="template-pagination" aria-label="模板分页">
                <button className="button button-secondary" disabled={loading || page <= 1}
                        onClick={() => setParams({...Object.fromEntries(params), page: String(page - 1)})}>上一页
                </button>
                <span>{page} / {data.pages}</span>
                <button className="button button-secondary" disabled={loading || page >= data.pages}
                        onClick={() => setParams({...Object.fromEntries(params), page: String(page + 1)})}>下一页
                </button>
            </nav>}
            <div className="template-library-howto"><span>从灵感到作品</span><p>选择模板 <ArrowRight size={13}
                                                                                                     aria-hidden="true"/> 填写想法 <ArrowRight
                size={13} aria-hidden="true"/> 带入创作区</p></div>
        </section>
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

    return <Modal title={item?.title || '正在读取模板'} className="template-dialog template-use-dialog"
                  onClose={busy ? () => {
    } : onClose}>
        {!item ? <div className="template-use-state">{error ? <>
                <p className="template-error" role="alert">{error}</p>
                <button className="button button-secondary" onClick={() => setRevision(value => value + 1)}>重新加载
                </button>
            </> : <p role="status">正在准备填写项…</p>}</div>
            : <div className="template-use-layout">
                <aside className="template-use-preview">
                    <TemplateArtwork item={item}/>
                    <div className="template-use-caption">
                        <div className="template-use-tags"><span>{item.category.name}</span>
                            {item.tags.slice(0, 2).map(term => <span key={term.id}>{term.name}</span>)}
                        </div>
                        <p>{item.description}</p>
                    </div>
                </aside>
                <form onSubmit={apply} className="template-form template-use-form">
                    <div className="template-use-content">
                        <div className="template-use-intro">
                            <h3>{item.fields.length ? '写下你的想法' : '灵感已就绪'}</h3>
                            <p>{item.fields.length ? '从示例出发，定制属于你的画面。' : '直接使用，或展开下方提示词自由调整。'}</p>
                        </div>
                        <div className="template-fields">{item.fields.map((field, index) => <label key={field.key}
                                                                                                   htmlFor={'template-field-' + field.key}>
                            <span><span className="template-use-field-number"
                                        aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                                {field.label}{!field.required && <small>选填</small>}</span>
                            <textarea id={'template-field-' + field.key} value={values[field.key] || ''}
                                      required={field.required} maxLength={500} rows={2}
                                      placeholder={field.example} disabled={busy}
                                      onChange={event => changeField(field.key, event.target.value)}/>
                        </label>)}</div>
                        {changed && <p className="template-notice"
                                       role="status">填写项已改变，你手动修改的提示词仍被保留。需要更新时，请点击“重新组合”。</p>}
                        <details className="template-prompt-editor">
                            <summary>完整提示词 <span>{prompt.length} / 4000</span></summary>
                            <label className="visually-hidden" htmlFor="template-final-prompt">完整提示词</label>
                            <textarea id="template-final-prompt" value={prompt} rows={8} maxLength={4000}
                                      disabled={busy}
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
                    </div>
                    <div className="template-use-actions">
                        {error && <p className="template-error" role="alert">{error}</p>}
                        <p>下一步选择模型与画面参数<span>点击生成后才提交任务</span></p>
                        <button className="button button-primary"
                                disabled={busy || !prompt.trim() || prompt.length > 4000}>
                            {busy ? '正在准备…' : '带入创作区'}<ArrowUpRight size={18} aria-hidden="true"/>
                        </button>
                    </div>
                </form>
            </div>}
    </Modal>
}
