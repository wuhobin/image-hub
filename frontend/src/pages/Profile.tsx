import { useState } from 'react'
import type { AppState } from '../App'

export default function Profile({ app }: { app: AppState }) {
  const [retrying, setRetrying] = useState(false)
  const { quota, quotaError } = app
  const loading = app.initializing || (!quota && !quotaError) || retrying
  const metrics = [
    { label: '已用额度', value: quota?.used },
    { label: '总额度', value: quota?.total },
    { label: '剩余额度', value: quota?.remaining },
  ]

  async function retry() {
    setRetrying(true)
    try { await app.refreshQuota() } catch { /* 读取失败由共享额度状态展示。 */ }
    finally { setRetrying(false) }
  }

  return <main id="main" className="history-page profile-page">
    <div className="page-heading">
      <div><span className="section-kicker">我的账户</span><h1>个人中心</h1><p>查看你的上传额度与使用情况。</p></div>
    </div>
    <section className="profile-quota" aria-labelledby="profile-quota-title" aria-busy={loading}>
      <h2 id="profile-quota-title">上传额度</h2>
      <dl className="profile-metrics">
        {metrics.map(({ label, value }) => <div key={label}>
          <dt>{label}</dt>
          <dd>{loading ? <span className="skeleton-block profile-quota-skeleton" aria-label="正在加载" />
            : value == null ? '—' : <>{value}<span className="profile-quota-unit">次</span></>}</dd>
        </div>)}
      </dl>
      <p className="profile-quota-note">额度为累计上传次数，不按天重置；删除图片不会返还额度。</p>
    </section>
    <div className="profile-quota-status" role="status">
      {loading ? '正在读取额度…' : quotaError ? <button type="button" className="text-button" onClick={retry}>{quotaError}</button>
        : quota?.remaining === 0 ? '免费上传额度已用完。' : null}
    </div>
  </main>
}
