import type { AppState } from '../App'

/** 创作和上传共用额度展示；后台刷新保留数字，仅首次读取显示占位。 */
export function QuotaStatus({ app }: { app: Pick<AppState, 'user' | 'initializing' | 'quota' | 'quotaError' | 'refreshQuota'> }) {
  const loading = app.initializing || (!!app.user && !app.quota && !app.quotaError)

  return <span className={`upload-quota ${app.quota?.remaining === 0 ? 'quota-exhausted' : ''}`} role="status" aria-live="polite" aria-busy={loading}>
    {loading ? <><span className="quota-placeholder" aria-hidden="true" /><span className="visually-hidden">正在读取共享额度</span></>
      : app.user && <span className="quota-content">
        {app.quota && <>剩余共享额度：<span className="quota-number">{app.quota.remaining}</span> / <span className="quota-number">{app.quota.total}</span>{app.quota.reserved > 0 && <span className="quota-reserved">（已预留 {app.quota.reserved} 次）</span>}</>}
        {app.quotaError && <button type="button" className="text-button" title={app.quota ? '额度刷新失败，显示的是上次结果，请重试' : app.quotaError} onClick={() => void app.refreshQuota().catch(() => {})}>{app.quota ? '刷新失败，重试' : app.quotaError}</button>}
      </span>}
  </span>
}
