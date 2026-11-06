import test from 'node:test'
import assert from 'node:assert/strict'
import {
    composePrompt,
    examplePrompt,
    loadTemplateShowcase,
    pickTemplateCovers,
    readCreationOptions,
    templateCoverUrl,
    templateInput
} from './templates.ts'
import type {CreationTemplate} from './templates.ts'

test('模板只替换一轮字面占位符，保留用户输入并正确处理选填项', () => {
    assert.equal(composePrompt('画{{subject}}，{{style}}。', {
        subject: '$& {{style}}',
        style: ' 水彩 '
    }), '画$& {{style}}，水彩。')
    assert.equal(composePrompt('{{subject}} / {{subject}} / {{optional}}', {subject: '猫'}), '猫 / 猫 / ')
    assert.equal(examplePrompt({
        promptPattern: '一只{{subject}}',
        fields: [{key: 'subject', label: '主体', example: '橘猫', required: true}]
    }), '一只橘猫')
    assert.deepEqual(readCreationOptions(null), {modelId: '', size: '', quality: ''})
    assert.equal(composePrompt('{{constructor}}', {}), '')
    assert.equal(examplePrompt({promptPattern: '画面保留字面 {{text}}', fields: []}), '画面保留字面 {{text}}')
})

test('模板编辑回填封面 URL，未配置的模板提交空字符串', () => {
    const item: CreationTemplate = {
        id: 1, title: '测试模板', description: '',
        category: {id: 1, kind: 'CATEGORY', name: '个人创作', sortOrder: 0}, tags: [],
        promptPattern: '一只猫', fields: [], coverUrl: 'https://cdn.example.test/cover.webp?version=2',
        exampleUrl: 'https://cdn.example.test/example.png', sourceShareId: null, sourceAuthor: null,
        enabled: true, sortOrder: 0, updateTime: ''
    }
    assert.equal(templateInput(item).coverUrl, item.coverUrl)
    assert.equal(templateInput({...item, coverUrl: null}).coverUrl, '')
    assert.equal(templateInput(item).promptPattern, item.promptPattern)
    assert.equal(Object.hasOwn(templateInput(item), 'exampleUrl'), false)
})

function coverTemplate(id: number, overrides: Partial<CreationTemplate> = {}): CreationTemplate {
    return {
        id,
        title: '模板 ' + id,
        description: '',
        category: {id: 1, kind: 'CATEGORY', name: '个人创作', sortOrder: 0},
        tags: [],
        fields: [],
        promptPattern: '画一只猫',
        coverUrl: 'https://cdn.example.test/' + id + '.jpg',
        exampleUrl: null,
        sourceShareId: null,
        sourceAuthor: null,
        enabled: true,
        sortOrder: 0,
        updateTime: '', ...overrides
    }
}

test('展示封面与卡片共用 URL 优先级，兼容历史图片和默认封面', () => {
    const item = coverTemplate(1, {title: '个性头像', exampleUrl: 'https://cdn.example.test/legacy.jpg'})
    assert.equal(templateCoverUrl(item), item.coverUrl)
    assert.equal(templateCoverUrl({...item, coverUrl: null}), item.exampleUrl)
    assert.equal(templateCoverUrl({...item, coverUrl: null, exampleUrl: null}), '/template-covers/avatar.jpg')
    assert.equal(templateCoverUrl(coverTemplate(2, {coverUrl: null})), '')
})

test('随机封面无重复、不修改原列表，空列表与不足三张正常返回', t => {
    t.mock.method(Math, 'random', () => 0.999)
    const items = Array.from({length: 9}, (_, index) => coverTemplate(index + 1))
    const selected = pickTemplateCovers(items)
    assert.equal(selected.length, 3)
    assert.equal(new Set(selected.map(templateCoverUrl)).size, 3)
    assert.equal(selected[0].id, 9)
    assert.deepEqual(items.map(item => item.id), [1, 2, 3, 4, 5, 6, 7, 8, 9])
    t.mock.method(Math, 'random', () => 0)
    assert.deepEqual(pickTemplateCovers(items).map(item => item.id), [1, 2, 3])
    assert.deepEqual(pickTemplateCovers([]), [])
    assert.equal(pickTemplateCovers([items[0]]).length, 1)
    assert.equal(pickTemplateCovers([items[0], items[1], {
        ...items[1],
        id: 20
    }, coverTemplate(21, {coverUrl: null})]).length, 2)
})

test('随机展示读取所有公开分页且不带筛选，失败或取消时不返回不完整结果', async t => {
    const storage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
    Object.defineProperty(globalThis, 'localStorage', {configurable: true, value: {getItem: () => null}})
    t.after(() => {
        if (storage) Object.defineProperty(globalThis, 'localStorage', storage)
        else Reflect.deleteProperty(globalThis, 'localStorage')
    })
    t.mock.method(Math, 'random', () => 0.999)
    const controller = new AbortController()
    const calls: string[] = []
    const items = Array.from({length: 53}, (_, index) => coverTemplate(index + 1))
    t.mock.method(globalThis, 'fetch', async (url: RequestInfo | URL, init?: RequestInit) => {
        calls.push(String(url))
        assert.equal(init?.signal, controller.signal)
        assert.equal(init?.cache, 'no-store')
        return new Response(JSON.stringify({
            code: 200, data: {
                records: calls.length === 1 ? items.slice(0, 50) : items.slice(50), pages: 2
            }
        }))
    })
    assert.equal((await loadTemplateShowcase(controller.signal))[0].id, 53)
    assert.deepEqual(calls, ['/api/app/public/templates?page=1&pageSize=50', '/api/app/public/templates?page=2&pageSize=50'])

    let requests = 0
    t.mock.method(globalThis, 'fetch', async () => {
        requests++
        return new Response(JSON.stringify(requests === 1
            ? {code: 200, data: {records: items.slice(0, 50), pages: 2}}
            : {code: 503, message: '模板读取失败'}))
    })
    await assert.rejects(loadTemplateShowcase(controller.signal), /模板读取失败/)

    const cancelled = new AbortController()
    requests = 0
    t.mock.method(globalThis, 'fetch', async () => {
        requests++
        cancelled.abort()
        return new Response(JSON.stringify({code: 200, data: {records: items.slice(0, 50), pages: 2}}))
    })
    await assert.rejects(loadTemplateShowcase(cancelled.signal), {name: 'AbortError'})
    assert.equal(requests, 1)
})
