export type TemplateTerm = { id: number; kind: 'CATEGORY' | 'TAG'; name: string; sortOrder: number }
export type TemplateField = { key: string; label: string; example: string; required: boolean }
export type CreationTemplate = {
    id: number; title: string; description: string; category: TemplateTerm; tags: TemplateTerm[]
    promptPattern: string; fields: TemplateField[]; exampleUrl: string | null
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
    promptPattern: string; fields: TemplateField[]; enabled: boolean; sortOrder: number
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
