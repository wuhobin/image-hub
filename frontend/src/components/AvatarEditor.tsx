import {useEffect, useRef, useState} from 'react'
import {Check} from '@phosphor-icons/react/dist/csr/Check'
import {UploadSimple} from '@phosphor-icons/react/dist/csr/UploadSimple'
import {ImageSquare} from '@phosphor-icons/react/dist/csr/ImageSquare'
import {MagnifyingGlass} from '@phosphor-icons/react/dist/csr/MagnifyingGlass'
import type {AppState} from '../App'
import type {ImageList, ImageRecord} from '../lib/types'
import {api} from '../lib/api'
import {fileError} from '../lib/rules'
import {Avatar} from './Avatar'
import {Modal} from './Modal'
import './avatar.css'

type Choice = { file: File; preview: string } | { image: ImageRecord; preview: string }

/** 新头像只在确认保存时上传；图库按用户现有分页接口读取。 */
export function AvatarEditor({app, onClose}: { app: AppState; onClose: () => void }) {
    const [mode, setMode] = useState<'upload' | 'library'>('upload')
    const [choice, setChoice] = useState<Choice | null>(null)
    const [images, setImages] = useState<ImageList | null>(null)
    const [page, setPage] = useState(1)
    const [search, setSearch] = useState('')
    const [loading, setLoading] = useState(false)
    const [loadError, setLoadError] = useState('')
    const [revision, setRevision] = useState(0)
    const [error, setError] = useState('')
    const [busy, setBusy] = useState(false)
    const input = useRef<HTMLInputElement>(null)
    const saving = useRef(false)

    useEffect(() => {
        if (choice && 'file' in choice) return () => URL.revokeObjectURL(choice.preview)
    }, [choice])

    useEffect(() => {
        if (mode !== 'library') return
        const controller = new AbortController()
        setLoading(true)
        setLoadError('')
        const timer = setTimeout(() => {
            const query = new URLSearchParams({page: String(page), pageSize: '12', search: search.trim(), sort: 'desc'})
            void api<ImageList>('/images?' + query, {signal: controller.signal}).then(result => {
                if (controller.signal.aborted) return
                if (page > Math.max(1, result.page.pages)) {
                    setPage(Math.max(1, result.page.pages));
                    return
                }
                setImages(result)
            }).catch(error => {
                if (!controller.signal.aborted) setLoadError(error instanceof Error ? error.message : '图片加载失败，请重试')
            }).finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        }, search.trim() ? 250 : 0)
        return () => {
            controller.abort();
            clearTimeout(timer)
        }
    }, [mode, page, search, revision])

    function chooseFile(file?: File) {
        if (!file || saving.current) return
        const invalid = fileError(file)
        setError(invalid || '')
        if (!invalid) setChoice({file, preview: URL.createObjectURL(file)})
    }

    async function save() {
        if (!choice || saving.current) return
        saving.current = true
        setBusy(true)
        setError('')
        try {
            await app.updateAvatar('file' in choice ? choice.file : choice.image.id)
            onClose()
        } catch (error) {
            setError(error instanceof Error ? error.message : '头像保存失败，请重试')
        } finally {
            saving.current = false
            setBusy(false)
        }
    }

    return <Modal title="更换头像" onClose={() => {
        if (!saving.current) onClose()
    }} className="avatar-modal">
        <div className="avatar-editor-preview">
            <Avatar name={app.user} url={choice?.preview || app.avatarUrl} className="avatar-editor-image"/>
            <div><strong>{choice ? ('file' in choice ? choice.file.name : choice.image.name) : '当前头像'}</strong>
                <p>居中裁切为头像，保存前可预览效果。</p></div>
        </div>
        <div className="avatar-source" role="group" aria-label="头像来源">
            {([['upload', '上传新头像', UploadSimple], ['library', '从我的图片选择', ImageSquare]] as const).map(([value, label, Icon]) =>
                <button type="button" key={value} aria-pressed={mode === value} disabled={busy}
                        onClick={() => {
                            setMode(value);
                            setChoice(null);
                            setError('')
                        }}><Icon size={17} aria-hidden="true"/>{label}</button>)}
        </div>
        {mode === 'upload' ? <div className="avatar-upload">
            <input ref={input} type="file" className="visually-hidden" tabIndex={-1} disabled={busy}
                   aria-label="选择头像文件" accept="image/jpeg,image/png,image/webp,image/gif"
                   onChange={event => {
                       chooseFile(event.target.files?.[0]);
                       event.target.value = ''
                   }}/>
            <button type="button" className="button button-secondary" disabled={busy}
                    onClick={() => input.current?.click()}>
                <UploadSimple size={18} aria-hidden="true"/>{choice ? '重新选择图片' : '选择本地图片'}</button>
            <p>JPG、PNG、WebP 或 GIF，最大 10 MB。</p>
            <span>自动裁切缩小为头像，GIF 使用首帧。</span>
            <span>免费上传，单独保存，不占用图库积分。</span>
        </div> : <div className="avatar-library" aria-busy={loading}>
            <label className="avatar-search"><MagnifyingGlass size={17} aria-hidden="true"/>
                <input type="search" aria-label="搜索我的图片" placeholder="搜索图片名称" maxLength={255} value={search}
                       disabled={busy} onChange={event => {
                    setSearch(event.target.value);
                    setPage(1)
                }}/></label>
            {loadError ? <div className="avatar-library-state" role="alert"><p>{loadError}</p>
                    <button type="button" className="text-button" onClick={() => setRevision(value => value + 1)}>重新加载
                    </button>
                </div> :
                loading ? <div className="avatar-library-state" role="status">正在读取我的图片…</div> :
                    !images?.page.records.length ? <div className="avatar-library-state">
                            <ImageSquare size={28}
                                         aria-hidden="true"/><strong>{search.trim() ? '没有找到匹配图片' : '图库里还没有图片'}</strong>
                            <p>{search.trim() ? '换个关键词，或清空搜索后再试。' : '可以切换到“上传新头像”，选择本地图片。'}</p>
                        </div> :
                        <div className="avatar-image-grid" role="group" aria-label="选择一张图片作为头像">
                            {images.page.records.map(image => <label className="avatar-image-option" key={image.id}
                                                                     title={image.name}>
                                <input type="radio" name="avatar-image" value={image.id} disabled={busy}
                                       checked={!!choice && 'image' in choice && choice.image.id === image.id}
                                       onChange={() => {
                                           setChoice({image, preview: image.preview});
                                           setError('')
                                       }}/>
                                <img src={image.preview} alt="" loading="lazy"/>
                                <span className="avatar-image-check"><Check size={14} weight="bold" aria-hidden="true"/></span>
                                <span className="avatar-image-name">{image.name}</span>
                            </label>)}
                        </div>}
            {!loadError && images && images.page.pages > 1 &&
                <nav className="avatar-pagination" aria-label="头像图库分页">
                    <button type="button" className="button button-secondary" disabled={loading || busy || page <= 1}
                            onClick={() => setPage(value => value - 1)}>上一页
                    </button>
                    <span>{page} / {images.page.pages}</span>
                    <button type="button" className="button button-secondary"
                            disabled={loading || busy || page >= images.page.pages}
                            onClick={() => setPage(value => value + 1)}>下一页
                    </button>
                </nav>}
        </div>}
        {error && <p className="avatar-error" role="alert">{error}</p>}
        <div className="modal-actions">
            <button type="button" className="button button-secondary" disabled={busy} onClick={onClose}>取消</button>
            <button type="button" className="button button-primary" disabled={!choice || busy}
                    onClick={save}>{busy ? '保存中…' : '保存头像'}</button>
        </div>
    </Modal>
}
