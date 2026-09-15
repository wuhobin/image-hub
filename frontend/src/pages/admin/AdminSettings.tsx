import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { Check } from '@phosphor-icons/react/dist/csr/Check'
import { adminApi } from '../../lib/admin/api'
import type { AdminSettings } from '../../lib/admin/api'

// 仅注册已接入的配置分类；各分类组件独立负责表单、校验和保存。
const settingsGroups = [{ key: 'upload', label: '上传设置', Panel: UploadSettings }]

const message = (error: unknown) => error instanceof Error ? error.message : '配置读取失败，请稍后重试'

/** 配置分类与 URL 同步，刷新和浏览器前进后退时保持选中的分组。 */
export default function AdminSettingsPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const tabList = useRef<HTMLDivElement>(null)

  const activeGroup = settingsGroups.find(group => group.key === searchParams.get('group')) ?? settingsGroups[0]

  function selectGroup(key: string) {
    if (key === activeGroup.key) return
    setSearchParams(previous => {
      const next = new URLSearchParams(previous)
      next.set('group', key)
      return next
    })
  }

  /** Tab 使用左右方向键及 Home/End 切换，键盘焦点跟随选中的分类。 */
  function navigateTabs(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const direction = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? settingsGroups.length - 1
      : direction ? (index + direction + settingsGroups.length) % settingsGroups.length : -1
    if (next < 0) return
    event.preventDefault()
    selectGroup(settingsGroups[next].key)
    requestAnimationFrame(() => tabList.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus())
  }

  const tabs = <div className="admin-settings-tabs" role="tablist" aria-label="配置分类" ref={tabList}>
    {settingsGroups.map((group, index) => <button
      key={group.key}
      id={`settings-tab-${group.key}`}
      type="button"
      role="tab"
      aria-selected={activeGroup.key === group.key}
      aria-controls={`settings-panel-${group.key}`}
      tabIndex={activeGroup.key === group.key ? 0 : -1}
      className="admin-settings-tab"
      onClick={() => selectGroup(group.key)}
      onKeyDown={event => navigateTabs(event, index)}
    >{group.label}</button>)}
  </div>

  return <div className="admin-settings-page">
    <div className="admin-page-title">
      <div><span className="admin-eyebrow">平台设置</span><h1>配置管理</h1><p>按分类管理平台配置，保存后应用到对应功能。</p></div>
    </div>
    <activeGroup.Panel key={activeGroup.key} tabs={tabs} />
  </div>
}

/** 上传分类独立维护编辑状态；仅在保存成功后更新当前值，失败时保留输入供重试。 */
function UploadSettings({ tabs }: { tabs: ReactNode }) {
  const [settings, setSettings] = useState<AdminSettings | null>(null)

  const [value, setValue] = useState('')

  const [loading, setLoading] = useState(true)

  const [saving, setSaving] = useState(false)

  const [error, setError] = useState('')

  const [saved, setSaved] = useState(false)

  const [retry, setRetry] = useState(0)

  const submitting = useRef(false)

  const total = Number(value)

  const valid = value.trim() !== '' && Number.isInteger(total) && total >= 0 && total <= 2147483647

  const changed = !!settings && valid && total !== settings.freeUploadQuota

  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    void adminApi<AdminSettings>('/settings', { signal: controller.signal })
      .then(result => {
        if (!controller.signal.aborted) {
          setSettings(result)
          setValue(String(result.freeUploadQuota))
        }
      })
      .catch(error => { if (!controller.signal.aborted) setError(message(error)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [retry])

  /** 校验当前分组并阻止重复提交，保存不会重置用户已经消耗的额度。 */
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!changed || submitting.current) return
    submitting.current = true
    setSaving(true)
    setSaved(false)
    setError('')
    try {
      const result = await adminApi<AdminSettings>('/settings', { method: 'PUT', body: JSON.stringify({ freeUploadQuota: total }) })
      setSettings(result)
      setValue(String(result.freeUploadQuota))
      setSaved(true)
    } catch (error) {
      setError(message(error))
    } finally {
      submitting.current = false
      setSaving(false)
    }
  }

  return <section className="admin-settings-canvas" aria-label="平台配置">
    <header className="admin-settings-toolbar">
      {tabs}
      <div className="admin-settings-toolbar-meta">
        <span className="admin-settings-updated">{settings ? <>最近更新 <time>{settings.updateTime || '—'}</time></> : loading ? '正在读取配置' : '配置未加载'}</span>
        <div className="admin-settings-actions">
          <button type="button" className="button button-secondary" disabled={!settings || saving || value === String(settings.freeUploadQuota)}
            onClick={() => { if (settings) setValue(String(settings.freeUploadQuota)); setSaved(false); setError('') }}>撤销修改</button>
          <button type="submit" form="upload-settings-form" className="button button-primary" disabled={loading || !changed || saving}>{saving ? '正在保存…' : '保存修改'}</button>
        </div>
      </div>
    </header>
    <div id="settings-panel-upload" role="tabpanel" aria-labelledby="settings-tab-upload" tabIndex={0} className="admin-settings-panel">
      {loading ? <div className="admin-settings-state" role="status">正在读取配置…</div>
        : !settings ? <div className="admin-settings-state"><p role="alert">{error}</p><button className="button button-secondary" onClick={() => setRetry(count => count + 1)}>重新加载</button></div>
          : <form id="upload-settings-form" onSubmit={save} aria-busy={saving}>
            <section className="admin-settings-section" aria-labelledby="quota-settings-title">
              <div className="admin-settings-section-heading"><h2 id="quota-settings-title">上传额度</h2><p>统一设置每位用户可使用的免费累计上传次数。</p></div>
              <div className="admin-settings-fields">
                <label htmlFor="free-upload-quota">免费总额度</label>
                <div className="admin-settings-input">
                  <input id="free-upload-quota" type="number" inputMode="numeric" min="0" max="2147483647" step="1" required value={value} disabled={saving}
                    aria-describedby="quota-input-help quota-change-note" onChange={event => { setValue(event.target.value); setSaved(false); setError('') }} />
                  <span>次 / 用户</span>
                </div>
                <p id="quota-input-help" className="admin-settings-help">填写 0–2,147,483,647 之间的整数。设为 0 可暂停新上传。</p>
                <div className="admin-settings-change" id="quota-change-note" role="status">
                  <span>当前 {settings.freeUploadQuota.toLocaleString()} 次</span><ArrowRight size={16} aria-hidden="true" /><strong>{valid ? total.toLocaleString() + ' 次' : '请输入有效额度'}</strong>
                  <p>{valid && total === 0 ? '保存后将暂停普通用户上传，已有图片仍可查看和删除。'
                    : changed && total < settings.freeUploadQuota ? '降低额度不会清除已用次数；已用次数达到新总额的用户将无法继续上传。'
                      : '已用次数保持不变，剩余额度随新的总额度重新计算。'}</p>
                </div>
                {error && <p className="admin-error" role="alert">{error}</p>}
                {saved && <span className="admin-settings-saved" role="status"><Check size={16} aria-hidden="true" />配置已保存</span>}
              </div>
            </section>
            <section className="admin-settings-section" aria-labelledby="quota-rules-title">
              <div className="admin-settings-section-heading"><h2 id="quota-rules-title">生效规则</h2><p>调整总额，保留已有消耗。</p></div>
              <div className="admin-settings-rules">
                <p>对新用户和已有用户统一生效，不按天重置。每成功上传一张图片消耗一次，删除图片不返还次数。</p>
                <div className="admin-settings-example"><span>例如，用户已上传 30 次</span><p>总额设为 <b>200</b><ArrowRight size={14} aria-hidden="true" />剩余 <b>170</b></p><p>总额设为 <b>20</b><ArrowRight size={14} aria-hidden="true" />剩余 <b>0</b></p></div>
                <p>调高额度后可继续使用新增额度；已经开始的上传会继续完成。</p>
              </div>
            </section>
          </form>}
    </div>
  </section>
}
