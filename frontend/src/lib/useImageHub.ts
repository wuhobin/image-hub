import { useEffect, useRef, useState } from 'react'
import {api, ApiError, getToken, prepareAvatar, SESSION_EXPIRED, TOKEN_KEY, uploadImage} from './api'
import {createQuotaCache} from './quotaCache'
import { fileError, MAX_FILES } from './rules'
import type { ImageRecord, LoginResult, PendingImage, UploadQuota, User } from './types'

type Preview = Pick<ImageRecord, 'name' | 'preview' | 'size' | 'width' | 'height'> & Partial<Pick<ImageRecord, 'url'>>
type Notice = { text: string; error: boolean }

export function useImageHub(onUploadPage: boolean, onProfilePage: boolean) {
    const [account, setAccount] = useState<User | null>(null)
    const user = account?.username || null
  const [initializing, setInitializing] = useState(!!getToken())
  const [pending, setPending] = useState<PendingImage[]>([])
  const [preview, setPreview] = useState<Preview | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [selecting, setSelecting] = useState(false)
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)
  const [quota, setQuota] = useState<UploadQuota | null>(null)
  const [quotaError, setQuotaError] = useState('')
  const ownedUrls = useRef(new Set<string>())
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const selectionLock = useRef(false)
  const uploadLock = useRef(false)
  const activeUpload = useRef<AbortController | null>(null)
  const generation = useRef(0)
  const quotaRequest = useRef(0)
    const profileRequest = useRef(0)
    const [quotaCache] = useState(() => createQuotaCache(signal => api<UploadQuota>('/images/quota', {signal})))

  useEffect(() => {
    if (!user || !(onUploadPage || onProfilePage) || busy) return
    const controller = new AbortController()
      void refreshQuota(controller.signal, false).catch(() => {
      })
      const refresh = () => {
          if (!document.hidden) void refreshQuota(controller.signal, false).catch(() => {
          })
      }
    window.addEventListener('focus', refresh)
    return () => { controller.abort(); window.removeEventListener('focus', refresh) }
  }, [user, onUploadPage, onProfilePage, busy])

    async function refreshQuota(signal?: AbortSignal, force = true) {
        signal?.throwIfAborted()
    const version = generation.current
    const request = ++quotaRequest.current
    try {
        const result = await quotaCache.read(force)
        signal?.throwIfAborted()
      // 焦点刷新、任务轮询和上传预检可能重叠；较早的响应不能覆盖新积分。
      if (version === generation.current && request === quotaRequest.current && !signal?.aborted) { setQuota(result); setQuotaError('') }
      return result
    } catch (error) {
        if (version === generation.current && request === quotaRequest.current && !signal?.aborted
            && !(error instanceof DOMException && error.name === 'AbortError')) {
        // 保留上次数字避免闪动；错误标记阻止用过期积分发起新的提交。
        setQuotaError('积分读取失败，点击重试')
      }
      throw error
    }
  }

  useEffect(() => {
    // 结果仅供本次上传页面查看；批次进行中保留完整队列，避免影响总进度。
    if (onUploadPage || busy) return
    const completed = pending.filter(item => item.status === 'done')
    if (!completed.length) return
    completed.forEach(item => release(item.preview))
    setPreview(current => completed.some(item => item.preview === current?.preview) ? null : current)
    setPending(current => current.filter(item => item.status !== 'done'))
  }, [onUploadPage, busy, pending])

  useEffect(() => {
    let controller = new AbortController()
    async function restore() {
      controller.abort()
      controller = new AbortController()
      const signal = controller.signal
      if (!getToken()) { setInitializing(false); return }
      setInitializing(true)
      try {
        const result = await api<User>('/auth/me', { signal })
          if (!signal.aborted) setAccount(result)
      } catch (error) {
        if (!signal.aborted) notify(error instanceof Error ? error.message : '登录状态读取失败', true)
      } finally {
        if (!signal.aborted) setInitializing(false)
      }
    }
    const expired = () => {
      clearSession(false)
      notify('登录已过期，请重新登录', true)
    }
    const sync = (event: StorageEvent) => {
      if (event.key !== TOKEN_KEY) return
      clearSession(true)
      void restore()
    }
    window.addEventListener(SESSION_EXPIRED, expired)
    window.addEventListener('storage', sync)
    void restore()
    return () => {
      controller.abort()
      generation.current++
        quotaCache.reset()
      activeUpload.current?.abort()
      window.removeEventListener(SESSION_EXPIRED, expired)
      window.removeEventListener('storage', sync)
      clearTimeout(noticeTimer.current)
      ownedUrls.current.forEach(url => URL.revokeObjectURL(url))
      ownedUrls.current.clear()
    }
  }, [])

  function notify(text: string, error = false) {
    clearTimeout(noticeTimer.current)
    setNotice({ text, error })
    noticeTimer.current = setTimeout(() => setNotice(null), 4200)
  }

  function release(url: string) {
    if (ownedUrls.current.delete(url)) URL.revokeObjectURL(url)
  }

  function clearSession(clearSelection: boolean) {
    generation.current++
      quotaCache.reset()
    activeUpload.current?.abort()
    uploadLock.current = false
    setBusy(false)
      setAccount(null)
    setQuota(null)
    setQuotaError('')
    setPreview(null)
    setRevision(value => value + 1)
    if (clearSelection) {
      ownedUrls.current.forEach(url => URL.revokeObjectURL(url))
      ownedUrls.current.clear()
      setPending([])
    } else {
      setPending(current => current.filter(item => {
        if (item.status === 'done') { release(item.preview); return false }
        return true
      }).map(item => ({ ...item, status: 'ready', progress: 0, record: undefined })))
    }
  }

  async function selectFiles(files: FileList | File[]) {
    if (selectionLock.current || uploadLock.current) return
    selectionLock.current = true
    setSelecting(true)
    const selected: PendingImage[] = []
    const errors: string[] = []
    const version = generation.current
    const available = MAX_FILES - pending.length
    if (files.length > available) errors.push(`最多选择 ${MAX_FILES} 张图片`)
    try {
      for (const file of Array.from(files).slice(0, available)) {
        const error = fileError(file)
        if (error) { errors.push(`${file.name}：${error}`); continue }
        const url = URL.createObjectURL(file)
        ownedUrls.current.add(url)
        try {
          const img = new Image()
          img.src = url
          await img.decode()
          if (version !== generation.current) { release(url); continue }
          selected.push({ id: crypto.randomUUID(), file, preview: url,
            width: img.naturalWidth, height: img.naturalHeight, progress: 0, status: 'ready' })
        } catch {
          release(url)
          errors.push(`${file.name} 无法读取，请换一张图片`)
        }
      }
      if (version !== generation.current) { selected.forEach(item => release(item.preview)); return }
      setPending(current => [...current, ...selected])
      if (errors.length) notify(errors[0], true)
    } finally {
      selectionLock.current = false
      setSelecting(false)
    }
  }

  function removePending(id: string) {
    if (uploadLock.current) return
    const item = pending.find(image => image.id === id)
    if (item) release(item.preview)
    setPending(current => current.filter(image => image.id !== id))
  }

  function clearPending() {
    if (uploadLock.current) return
    pending.forEach(item => release(item.preview))
    setPending([])
  }

  async function upload() {
    if (!user || uploadLock.current || selectionLock.current) return
    const items = pending.filter(item => item.status === 'ready' || item.status === 'error')
    if (!items.length) return
    uploadLock.current = true
    setBusy(true)
    const controller = new AbortController()
    activeUpload.current = controller
    const version = generation.current
    let succeeded = 0
      let attemptedUpload = false
    let quotaStopped = false
    try {
      // 整批预检在任何文件发送前完成；后端仍逐张校验，防止另一设备抢占积分。
      const available = await refreshQuota(controller.signal)
      if (controller.signal.aborted || version !== generation.current) return
      if (items.length > available.remaining) {
        notify(available.remaining === 0 ? '积分已用完'
          : `剩余 ${available.remaining} 积分，请将本次图片减少至 ${available.remaining} 张`, true)
        return
      }
      for (const item of items) {
        if (controller.signal.aborted || version !== generation.current) break
        setPending(current => current.map(image => image.id === item.id ? { ...image, status: 'uploading', progress: 0, error: undefined } : image))
        try {
            attemptedUpload = true
          const record = await uploadImage(item.file, progress => {
            if (version === generation.current) setPending(current => current.map(image => image.id === item.id ? { ...image, progress } : image))
          }, controller.signal)
          if (version !== generation.current) break
          setPending(current => current.map(image => image.id === item.id ? { ...image, status: 'done', progress: 100, record } : image))
          succeeded++
          setQuota(current => current ? { ...current, remaining: Math.max(0, current.remaining - 1) } : current)
          setRevision(value => value + 1)
        } catch (error) {
          if (controller.signal.aborted || version !== generation.current) break
          setPending(current => current.map(image => image.id === item.id
            ? { ...image, status: 'error', progress: 0, error: error instanceof Error ? error.message : '上传失败，请重试' } : image))
          if (error instanceof ApiError && [40301, 409, 503].includes(error.code)) {
            quotaStopped = true
            if (error.code === 40301) setQuota(current => current ? { ...current, remaining: 0 } : current)
            notify(`${error.message}；已成功 ${succeeded} 张，其余图片保留在列表中`, true)
            break
          }
        }
      }
      if (!quotaStopped && version === generation.current) notify(succeeded === items.length
        ? `${succeeded} 张图片上传完成` : `${succeeded} 张成功，${items.length - succeeded} 张失败，可重试失败图片`, succeeded !== items.length)
    } catch (error) {
      if (!controller.signal.aborted && version === generation.current) notify(error instanceof Error ? error.message : '积分校验失败，请重试', true)
    } finally {
      if (version === generation.current) {
        uploadLock.current = false
        setBusy(false)
        activeUpload.current = null
          // 上传可能在响应丢失前已扣分，批次结束后始终查询真实余额。
          if (attemptedUpload) void refreshQuota().catch(() => {
          })
      }
    }
  }

  async function authenticate(username: string, password: string) {
    const result = await api<LoginResult>('/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) })
      generation.current++
      quotaCache.reset()
      setQuota(null)
      setQuotaError('')
    localStorage.setItem(TOKEN_KEY, result.token)
      setAccount(result.user)
    setInitializing(false)
    setRevision(value => value + 1)
  }

  async function logout() {
    try {
      await api<void>('/auth/logout', { method: 'POST' })
      localStorage.removeItem(TOKEN_KEY)
      clearSession(true)
      notify('已退出登录')
    } catch (error) {
      notify(error instanceof Error ? error.message : '退出失败，请重试', true)
    }
  }

  async function deleteRecord(id: string) {
    await api<void>('/images/' + encodeURIComponent(id), { method: 'DELETE' })
    const selected = pending.find(item => item.record?.id === id)
    if (selected) release(selected.preview)
    setPending(current => current.filter(item => item.record?.id !== id))
    setPreview(null)
    setRevision(value => value + 1)
    notify('图片和上传记录已删除')
      void refreshUser().catch(() => {
      })
  }

    async function refreshUser() {
        const version = generation.current
        const request = ++profileRequest.current
        const result = await api<User>('/auth/me')
        if (version === generation.current && request === profileRequest.current) setAccount(result)
    }

    async function updateAvatar(choice: File | string) {
        const version = generation.current
        const request = ++profileRequest.current
        const body = typeof choice === 'string' ? JSON.stringify({imageId: choice}) : new FormData()
        if (body instanceof FormData) body.append('file', await prepareAvatar(choice as File))
        // 本地处理期间可能退出或切换账号，不能用新会话替旧账号上传头像。
        if (version !== generation.current) throw new Error('登录状态已变化，请重新选择头像')
        try {
            const result = await api<User>('/auth/avatar', {
                method: typeof choice === 'string' ? 'PUT' : 'POST', body, signal: AbortSignal.timeout(120000),
            })
            if (version === generation.current && request === profileRequest.current) {
                setAccount(result)
                notify('头像已更新')
            }
        } catch (error) {
            // 响应丢失时回读账户，避免头像实际上已更新、页面仍显示旧状态。
            if (version === generation.current) void refreshUser().catch(() => {
            })
            throw error
        }
  }

  async function copyUrl(url: string) {
    try { await navigator.clipboard.writeText(url); notify('原始链接已复制') }
    catch { notify('复制失败，请选中链接后手动复制', true) }
  }

    return {
        user,
        avatarUrl: account?.avatarUrl,
        refreshUser,
        updateAvatar,
        initializing,
        quota,
        quotaError,
        refreshQuota,
        pending,
        preview,
        setPreview,
        notice,
        setNotice,
        selecting,
    busy, revision, selectFiles, removePending, clearPending, upload, authenticate, logout, deleteRecord, copyUrl, notify }
}
