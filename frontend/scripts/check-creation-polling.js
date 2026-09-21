// 本地浏览器回归检查：先启动 npm run dev，用 agent-browser 在隔离会话打开首页，
// 再用 eval -b 执行本文件的 Base64。仅模拟当前浏览器的 API 与轮询定时器，不发送真实生成请求。
// PowerShell（frontend 目录）：agent-browser --session polling-test open http://127.0.0.1:5173
// agent-browser --session polling-test eval -b ([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Get-Content -Raw -Encoding UTF8 scripts/check-creation-polling.js))))
(async () => {
  const assert = (condition, message) => { if (!condition) throw new Error(message) }
  assert(['127.0.0.1', 'localhost'].includes(location.hostname) && location.pathname === '/' && !localStorage.getItem('imagehub.token'), '仅在本地首页的隔离游客会话运行')
  const originalFetch = window.fetch
  const originalSetTimeout = window.setTimeout
  const originalClearTimeout = window.clearTimeout
  const timers = new Map()
  const calls = []
  let timerId = -1
  let task = null
  let failActive = false
  let failHistory = false
  let holdHistory = false
  let releaseHistory = null
  let uncertainPost = false
  let request = null
  const taskData = { id: 'test-task', requestId: 'test-request', modelName: 'Test model', prompt: '测试描述', size: '1024x1024', quality: 'medium', createTime: '2026-09-16 12:00:00', image: null, errorMessage: null, resultExpiresAt: null }
  const pageData = records => ({ records, total: 13, current: 1, size: 12, pages: 2 })
  const settle = () => new Promise(resolve => originalSetTimeout(resolve, 100))
  const count = path => calls.filter(call => call === path).length
  const advance = async () => {
    const scheduled = [...timers.values()]
    timers.clear()
    scheduled.forEach(callback => callback())
    await settle()
  }
  const snapshot = () => [count('/generations/active'), count('/generations/test-task'), count('/images/quota'), count('/generations?page=1&pageSize=12'), count('/generations?page=2&pageSize=12')]
  const same = (actual, expected, message) => assert(JSON.stringify(actual) === JSON.stringify(expected), message + ': ' + JSON.stringify(actual))
  window.setTimeout = (callback, delay, ...args) => {
    if (delay !== 5000 && delay !== 2000) return originalSetTimeout(callback, delay, ...args)
    const id = timerId--
    timers.set(id, () => callback(...args))
    return id
  }
  window.clearTimeout = id => { timers.delete(id); originalClearTimeout(id) }
  window.fetch = async (input, init) => {
    // 开发模式 StrictMode 会撤销首次 effect；被取消的请求不计入完成次数。
    await Promise.resolve()
    if (init?.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
    const url = new URL(input, location.origin)
    assert(url.pathname.startsWith('/api/app/'), '测试不允许访问非模拟接口')
    const path = url.pathname.slice('/api/app'.length) + url.search
    calls.push(path)
    let data
    if (path === '/auth/me') data = { id: 'test-user', username: 'Polling test' }
    else if (path === '/generations/models') data = [{ id: 1, name: 'Test model', sizes: ['1024x1024'], defaultSize: '1024x1024', qualities: ['medium'], defaultQuality: 'medium', pointsCost: 1 }]
    else if (path === '/images/quota') data = { remaining: 9, total: 10, used: 1, reserved: task && task.status !== 'SUCCEEDED' ? 1 : 0 }
    else if (path === '/generations/active') {
      if (failActive) { failActive = false; throw new TypeError('模拟任务查询断网') }
      data = task && ['QUEUED', 'GENERATING', 'SAVING'].includes(task.status) ? task : null
    } else if (path.startsWith('/generations?page=')) {
      data = path.includes('page=2') ? pageData([{ ...taskData, id: 'older-task', status: 'SUCCEEDED' }]) : pageData(task ? [task] : [])
      if (holdHistory) await new Promise((resolve, reject) => {
        releaseHistory = resolve
        init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
      })
      if (failHistory) { failHistory = false; throw new TypeError('模拟历史查询断网') }
    } else if (path === '/generations/test-task') {
      if (failActive) { failActive = false; throw new TypeError('模拟任务查询断网') }
      data = task
    }
    else if (path === '/generations' && init?.method === 'POST') {
      request = JSON.parse(init.body)
      if (uncertainPost) { task = null; throw new TypeError('模拟提交响应丢失') }
      task = { ...taskData, ...request, status: 'QUEUED' }
      data = task
    } else throw new Error('未模拟的接口: ' + path)
    return new Response(JSON.stringify({ code: 200, data }), { headers: { 'Content-Type': 'application/json' } })
  }
  try {
    // 触发真实 App 的登录恢复流程，随后测试真实 Create 组件。
    localStorage.setItem('imagehub.token', 'local-polling-test')
    window.dispatchEvent(new StorageEvent('storage', { key: 'imagehub.token' }))
    await settle()
    same(snapshot(), [1, 0, 1, 1, 0], '进入空闲页面只加载一次')
    assert(timers.size === 0, '空闲页面不应安排轮询')
    await advance()
    same(snapshot(), [1, 0, 1, 1, 0], '空闲期间不能持续请求')

    task = { ...taskData, status: 'QUEUED' }
    window.dispatchEvent(new Event('focus'))
    await settle()
    same(snapshot(), [2, 0, 2, 2, 0], '切回页面应恢复任务')
    await advance()
    same(snapshot(), [2, 1, 2, 2, 0], '任务未变化时只轮询已知任务详情')
    task = { ...task, status: 'GENERATING' }
    await advance()
    same(snapshot(), [2, 2, 2, 2, 0], '中间状态仅更新当前行，不重复读取积分和历史')
    failActive = true
    await advance()
    assert(timers.size === 1, '任务查询失败后应继续重试')
    await advance()
    same(snapshot(), [2, 4, 2, 2, 0], '恢复后状态未变不重复加载关联数据')

    const nextPage = [...document.querySelectorAll('button')].find(button => button.textContent === '下一页')
    nextPage.click()
    await settle()
    assert(count('/generations?page=2&pageSize=12') === 1, '翻页应加载对应历史')
    task = { ...task, status: 'SUCCEEDED' }
    failHistory = true
    const beforeCompletion = count('/generations/test-task')
    await advance()
    assert(timers.size === 1, '任务已结束，失败历史仍应独立重试')
    await advance()
    assert(count('/generations/test-task') === beforeCompletion + 1, '历史重试不再查询已完成任务')
    assert(timers.size === 0, '任务结束且刷新成功后应停止轮询')
    assert(count('/generations/models') === 1, '焦点和状态变化不应反复加载模型')
    const completedCalls = calls.length
    await advance()
    assert(calls.length === completedCalls, '结束后不能持续请求')

    const textarea = document.querySelector('#creation-prompt')
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(textarea, '回归测试描述')
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await settle()
    holdHistory = true
    document.querySelector('.creation-submit').click()
    await settle()
    assert(!document.querySelector('main > .creation-result'), '提交后不再出现独立大预览')
    assert(document.querySelector('.creation-history-item.is-running .creation-status')?.textContent === '等待生成', '历史请求未返回时就显示新任务卡片')
    assert(getComputedStyle(document.querySelector('.is-running .creation-history-thumb'), '::after').animationName === 'creation-orbit', '进行中的卡片应显示生成动效')
    holdHistory = false
    releaseHistory()
    releaseHistory = null
    await settle()
    assert(count('/generations') === 1 && timers.size === 1, '从空闲提交任务后应重新启动轮询')
    // 切回页面触发慢历史；即使它尚未返回，任务也必须继续每轮更新。
    holdHistory = true
    window.dispatchEvent(new Event('focus'))
    await settle()
    const beforeSlowHistory = count('/generations/test-task')
    task = { ...task, status: 'GENERATING' }
    await advance()
    assert(count('/generations/test-task') === beforeSlowHistory + 1, '慢历史不能阻塞任务查询')
    assert(document.querySelector('.creation-history-item.is-running .creation-status')?.textContent === '正在生成', '慢历史期间展示最新状态')
    holdHistory = false
    releaseHistory()
    releaseHistory = null
    await settle()
    assert(document.querySelector('.creation-history-item .creation-status')?.textContent === '正在生成', '较早历史响应不能覆盖最新任务状态')
    task = { ...task, status: 'SUCCEEDED' }
    holdHistory = true
    await advance()
    assert(typeof releaseHistory === 'function', '历史请求应处于等待中')
    assert(!document.querySelector('.creation-history-item.is-running') && !document.querySelector('main > .creation-result'), '完成后停止卡片动效，不新增大预览')
    assert(document.querySelector('.creation-history-item .creation-status')?.textContent === '已完成', '历史仍未返回时就展示本次生成结果')
    holdHistory = false
    releaseHistory()
    releaseHistory = null
    await settle()
    assert(timers.size === 0, '本次生成结束后停止')

    const nextHistoryPage = [...document.querySelectorAll('button')].find(button => button.textContent === '下一页')
    nextHistoryPage.click()
    await settle()
    uncertainPost = true
    document.querySelector('.creation-submit').click()
    await settle()
    assert(timers.size === 1, '提交结果不明时必须持续确认')
    task = { ...taskData, ...request, status: 'SUCCEEDED' }
    await advance()
    assert(timers.size === 0 && !textarea.disabled, '从历史确认已结束提交后解除锁定并停止')
    assert(count('/generations') === 2, '查询状态不能自动重放生成请求')

    task = { ...taskData, status: 'GENERATING' }
    window.dispatchEvent(new Event('focus'))
    await settle()
    assert(timers.size === 1, '进行中的任务需要继续查询状态')
    document.querySelector('.navigation a[href="/upload"]').click()
    await settle()
    assert(timers.size === 0, '离开页面必须清理轮询定时器')
    return 'PASS: independent polling/history, quota refresh boundaries, model requests, stale history, failures, pagination, submission, uncertain result, cleanup'
  } finally {
    if (releaseHistory) releaseHistory()
    localStorage.removeItem('imagehub.token')
    window.dispatchEvent(new StorageEvent('storage', { key: 'imagehub.token' }))
    await settle()
    window.fetch = originalFetch
    window.setTimeout = originalSetTimeout
    window.clearTimeout = originalClearTimeout
  }
})()
