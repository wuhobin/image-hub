import {useLayoutEffect, useRef, type ReactNode} from 'react'
import './masonry.css'

/** 保持作品的 DOM 顺序，按实际卡片高度填入较短列；图片、字体和窗口变化都会重新测量。 */
export default function MasonryGrid({children, className}: { children: ReactNode; className: string }) {
    const gallery = useRef<HTMLDivElement>(null)

    useLayoutEffect(() => {
        const cards = gallery.current?.querySelectorAll<HTMLElement>(':scope > *')
        if (!cards?.length) return
        const layout = () => {
            const heights = Array.from(cards, card => Math.ceil(card.offsetHeight + 32))
            cards.forEach((card, index) => {
                card.style.gridRowEnd = 'span ' + heights[index]
            })
        }
        layout()
        const observer = new ResizeObserver(layout)
        cards.forEach(card => observer.observe(card))
        return () => observer.disconnect()
    }, [children])

    return <div className={'masonry-grid ' + className} ref={gallery}>{children}</div>
}
