import { Link } from 'react-router-dom'
import { Sparkle } from '@phosphor-icons/react/dist/csr/Sparkle'
import { getHistoryView, type HistoryView } from './HistoryViewToggle'

export function HistoryHeading() {
  return <div className="page-heading">
    <div><span className="section-kicker">属于你的图片空间</span><h1>我的图片</h1><p>AI 创作与本地上传，统一保存、查看和分享。</p></div>
    <Link className="button button-primary" to="/"><Sparkle size={17} />开始创作</Link>
  </div>
}

export function HistoryCardsSkeleton({ view = getHistoryView() }: { view?: HistoryView }) {
  return <div role="status" aria-label="正在加载图片">
    <span className="visually-hidden">正在加载图片…</span>
    <div className={`image-library history-skeleton-grid ${view === 'list' ? 'is-list' : ''}`} aria-hidden="true">
      {Array.from({ length: 8 }, (_, index) => <div className="history-skeleton-card" key={index}>
        <div className="library-preview skeleton-block" />
        <div className="library-card-body">
          <span className="skeleton-block skeleton-title" />
          <span className="skeleton-block skeleton-detail" />
          <div className="image-card-bottom"><span className="skeleton-block skeleton-date" /><span className="skeleton-block skeleton-actions" /></div>
        </div>
      </div>)}
    </div>
  </div>
}

// 路由代码、登录态和首批数据等待共用布局，避免加载阶段切换时页面塌缩。
export default function HistoryLoading({ view = getHistoryView() }: { view?: HistoryView }) {
  return <main id="main" className="history-page" aria-busy="true">
    <HistoryHeading />
    <div className="library-summary" aria-hidden="true">
      <div><strong className="skeleton-block skeleton-count">&nbsp;</strong><span>张图片</span></div>
      <span className="summary-divider" />
      <div><strong className="skeleton-block skeleton-total">&nbsp;</strong><span>已上传大小</span></div>
      <span className="sample-note">你的 AI 创作与上传</span>
    </div>
    <div className="library-toolbar" aria-hidden="true">
      <div className="search-field"><span className="skeleton-block skeleton-search" /></div>
      <div className="filter-group"><span className="skeleton-block skeleton-filter" /><span className="skeleton-block skeleton-sort sort-label" /><span className="skeleton-block skeleton-view-toggle" /></div>
    </div>
    <HistoryCardsSkeleton view={view} />
  </main>
}
