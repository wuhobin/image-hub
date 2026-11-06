import {api} from './api.ts'
import type {Page} from './types'

export type TemplateTerm = { id: number; kind: 'CATEGORY' | 'TAG'; name: string; sortOrder: number }
export type TemplateField = { key: string; label: string; example: string; required: boolean }
export type CreationTemplate = {
    id: number; title: string; description: string; category: TemplateTerm; tags: TemplateTerm[]
    promptPattern: string; fields: TemplateField[]; exampleUrl: string | null; coverUrl: string | null
    sourceShareId: string | null; sourceAuthor: string | null; enabled: boolean; sortOrder: number; updateTime: string
}
export type TemplateDraft = {
    id: number;
    title: string;
    values: Record<string, string>;
    prompt: string;
    manual: boolean
}
export type TemplateInput = {
    title: string; description: string; categoryId: number; tagIds: number[]
    promptPattern: string; fields: TemplateField[]; coverUrl: string; enabled: boolean; sortOrder: number
}

const templateCovers = new Map([
    ['个性头像', 'avatar'], ['手机 / 电脑壁纸', 'wallpaper'], ['节日祝福图', 'holiday'],
    ['小红书封面', 'social'], ['公众号配图', 'article'], ['视频封面', 'video'],
    ['商品主图', 'product'], ['商品场景图', 'lifestyle'], ['促销海报', 'poster']
])

/** 展示区和模板卡片使用同一封面来源，保留历史示例及内置封面兼容。 */
export function templateCoverUrl(item: CreationTemplate): string {
    const fallback = templateCovers.get(item.title)
    return item.coverUrl || item.exampleUrl || (fallback ? '/template-covers/' + fallback + '.jpg' : '')
}

/** 无放回抽样，跳过无封面与重复图片，不改变原列表顺序。 */
export function pickTemplateCovers(items: CreationTemplate[]): CreationTemplate[] {
    const seen = new Set<string>()
    const candidates = items.filter(item => {
        const source = templateCoverUrl(item)
        if (!source || seen.has(source)) return false
        seen.add(source)
        return true
    })
    for (let index = 0; index < Math.min(3, candidates.length); index++) {
        const selected = index + Math.floor(Math.random() * (candidates.length - index))
        const current = candidates[index]
        candidates[index] = candidates[selected]
        candidates[selected] = current
    }
    return candidates.slice(0, 3)
}

/** 独立读取全部公开分页后抽样，避免展示范围受当前分类、搜索或第一页影响。 */
export async function loadTemplateShowcase(signal: AbortSignal): Promise<CreationTemplate[]> {
    signal.throwIfAborted()
    const first = await api<Page<CreationTemplate>>('/public/templates?page=1&pageSize=50', {signal, cache: 'no-store'})
    const items = [...first.records]
    for (let page = 2; page <= first.pages; page++) {
        signal.throwIfAborted()
        const next = await api<Page<CreationTemplate>>('/public/templates?page=' + page + '&pageSize=50', {
            signal,
            cache: 'no-store'
        })
        items.push(...next.records)
    }
    signal.throwIfAborted()
    return pickTemplateCovers(items)
}

/** 一次字面替换，不把填写内容再当占位符解析，也不执行表达式。 */
export function composePrompt(pattern: string, values: Record<string, string>): string {
    return pattern.replace(/\{\{([a-z][a-z0-9_]{0,31})}}/g, (_, key: string) => {
        const value = Object.hasOwn(values, key) ? values[key] : ''
        return typeof value === 'string' ? value.trim() : ''
    })
}

export function templateInput(item: CreationTemplate): TemplateInput {
    return {
        title: item.title, description: item.description, categoryId: item.category.id,
        tagIds: item.tags.map(tag => tag.id), promptPattern: item.promptPattern,
        coverUrl: item.coverUrl || '',
        fields: item.fields.map(field => ({...field})), enabled: item.enabled, sortOrder: item.sortOrder
    }
}

export function examplePrompt(item: Pick<CreationTemplate, 'promptPattern' | 'fields'>): string {
    if (!item.fields.length) return item.promptPattern
    return composePrompt(item.promptPattern, Object.fromEntries(item.fields.map(field => [field.key, field.example])))
}

type CreationOptions = { modelId: string; size: string; quality: string }

/** 只保存本标签页内的参数选择，不持久化提示词或身份凭据；存储受限时继续使用默认值。 */
export function readCreationOptions(user: string | null): CreationOptions {
    try {
        const value = JSON.parse(sessionStorage.getItem('imagehub.creation-options:' + user) || '{}')
        if (typeof value.modelId === 'string' && typeof value.size === 'string' && typeof value.quality === 'string') return value
    } catch { /* 存储不可用或旧值损坏时采用模型默认参数。 */
    }
    return {modelId: '', size: '', quality: ''}
}

export function saveCreationOptions(user: string, value: CreationOptions) {
    try {
        sessionStorage.setItem('imagehub.creation-options:' + user, JSON.stringify(value))
    } catch { /* 参数仍保留在当前页面。 */
    }
}
