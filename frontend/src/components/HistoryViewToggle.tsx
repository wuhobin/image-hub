import { GridFour } from '@phosphor-icons/react/dist/csr/GridFour'
import { ListBullets } from '@phosphor-icons/react/dist/csr/ListBullets'

export type HistoryView = 'grid' | 'list'
const preferenceKey = 'imghub.history-view'

export function getHistoryView(): HistoryView {
  try { return localStorage.getItem(preferenceKey) === 'list' ? 'list' : 'grid' }
  catch { return 'grid' }
}

export default function HistoryViewToggle({ view, onChange }: { view: HistoryView; onChange: (view: HistoryView) => void }) {
  function change(next: HistoryView) {
    onChange(next)
    try { localStorage.setItem(preferenceKey, next) }
    catch { /* 浏览器禁止存储时，仍允许本次页面切换。 */ }
  }

  return <div className="history-view-toggle" role="group" aria-label="图片展示形式">
    <button type="button" aria-label="宫格展示" aria-pressed={view === 'grid'} onClick={() => change('grid')}><GridFour size={16} aria-hidden="true" />宫格</button>
    <button type="button" aria-label="列表展示" aria-pressed={view === 'list'} onClick={() => change('list')}><ListBullets size={16} aria-hidden="true" />列表</button>
  </div>
}
