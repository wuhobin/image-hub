import {useState} from 'react'

/** 图片不可用时回退姓名首字；新地址不沿用旧图片的失败状态。 */
export function Avatar({name, url, className = 'avatar'}: {
    name: string | null;
    url?: string | null;
    className?: string
}) {
    const [failed, setFailed] = useState<string | null>(null)
    return <span className={className + ' user-avatar'} aria-hidden="true">
        {url && url !== failed ?
            <img src={url} alt="" onError={() => setFailed(url)}/> : name?.slice(0, 1).toUpperCase() || '·'}
    </span>
}
