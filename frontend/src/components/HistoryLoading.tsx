import { Link } from 'react-router-dom'
import { Plus } from '@phosphor-icons/react/dist/csr/Plus'

export function HistoryHeading() {
  return <div className="page-heading">
    <div><span className="section-kicker">属于你的图片空间</span><h1>每次分享，都在这里。</h1><p>查看、复制和管理你上传的每一张图片。</p></div>
    <Link className="button button-primary" to="/"><Plus size={17} />上传图片</Link>
  </div>
}

export function HistoryCardsSkeleton() {
  return <div role="status" aria-label="正在加载上传记录">
    <span className="visually-hidden">正在加载上传记录…</span>
    <div className="image-library history-skeleton-grid" aria-hidden="true">
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
export default function HistoryLoading() {
  return <main id="main" className="history-page" aria-busy="true">
    <HistoryHeading />
    <div className="library-summary" aria-hidden="true">
      <div><strong className="skeleton-block skeleton-count">&nbsp;</strong><span>张图片</span></div>
      <span className="summary-divider" />
      <div><strong className="skeleton-block skeleton-total">&nbsp;</strong><span>已上传大小</span></div>
      <span className="sample-note">仅显示你上传的图片</span>
    </div>
    <div className="library-toolbar" aria-hidden="true">
      <div className="search-field"><span className="skeleton-block skeleton-search" /></div>
      <div className="filter-group"><span className="skeleton-block skeleton-filter" /><span className="skeleton-block skeleton-sort" /></div>
    </div>
    <HistoryCardsSkeleton />
  </main>
}
