import { useEffect, useRef, useState } from 'react'
import type { FormEvent, KeyboardEvent, ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ArrowRight } from '@phosphor-icons/react/dist/csr/ArrowRight'
import { Check } from '@phosphor-icons/react/dist/csr/Check'
import { adminApi } from '../../lib/admin/api'
import type { AdminSettings } from '../../lib/admin/api'

// 仅注册已接入的配置分类；各分类组件独立负责表单、校验和保存。
const settingsGroups = [{key: 'upload', label: '积分设置', Panel: UploadSettings}, {
    key: 'check-in',
    label: '签到设置',
    Panel: CheckInSettings
}]

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


/** 签到奖励单独保存，修改不会重算已经到账的积分。 */
function CheckInSettings({tabs}: { tabs: ReactNode }) {
    const [settings, setSettings] = useState<{ dailyPoints: number; bonusPoints: number } | null>(null)
    const [daily, setDaily] = useState('')
    const [bonus, setBonus] = useState('')
    const [loading, setLoading] = useState(true)
    const [saving, setSaving] = useState(false)
    const [error, setError] = useState('')
    const [saved, setSaved] = useState(false)
    const [retry, setRetry] = useState(0)
    const submitting = useRef(false)
    const valid = [daily, bonus].every(value => value.trim() !== '' && Number.isInteger(Number(value)) && Number(value) >= 1 && Number(value) <= 2147483647)
    const changed = !!settings && valid && (Number(daily) !== settings.dailyPoints || Number(bonus) !== settings.bonusPoints)

    useEffect(() => {
        const controller = new AbortController()
        setLoading(true)
        setError('')
        void adminApi<{ dailyPoints: number; bonusPoints: number }>('/settings/check-in', {signal: controller.signal})
            .then(result => {
                if (controller.signal.aborted) return
                setSettings(result);
                setDaily(String(result.dailyPoints));
                setBonus(String(result.bonusPoints))
            })
            .catch(error => {
                if (!controller.signal.aborted) setError(message(error))
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false)
            })
        return () => controller.abort()
    }, [retry])

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault()
        if (!changed || submitting.current) return
        submitting.current = true;
        setSaving(true);
        setSaved(false);
        setError('')
        try {
            const result = await adminApi<{ dailyPoints: number; bonusPoints: number }>('/settings/check-in', {
                method: 'PUT', body: JSON.stringify({dailyPoints: Number(daily), bonusPoints: Number(bonus)}),
            })
            setSettings(result);
            setDaily(String(result.dailyPoints));
            setBonus(String(result.bonusPoints));
            setSaved(true)
        } catch (error) {
            setError(message(error))
        } finally {
            submitting.current = false;
            setSaving(false)
        }
    }

    return <section className="admin-settings-canvas" aria-label="签到配置">
        <header className="admin-settings-toolbar">
            {tabs}
            <div className="admin-settings-actions">
                <button type="button" className="button button-secondary" disabled={!settings || saving}
                        onClick={() => {
                            if (settings) {
                                setDaily(String(settings.dailyPoints));
                                setBonus(String(settings.bonusPoints))
                            }
                            setSaved(false);
                            setError('')
                        }}>撤销修改
                </button>
                <button type="submit" form="check-in-settings-form" className="button button-primary"
                        disabled={loading || !changed || saving}>{saving ? '正在保存…' : '保存修改'}</button>
            </div>
        </header>
        <div id="settings-panel-check-in" role="tabpanel" aria-labelledby="settings-tab-check-in" tabIndex={0}
             className="admin-settings-panel">
            {loading ? <div className="admin-settings-state" role="status">正在读取配置…</div>
                : !settings ? <div className="admin-settings-state"><p role="alert">{error}</p>
                        <button className="button button-secondary" onClick={() => setRetry(value => value + 1)}>重新加载
                        </button>
                    </div>
                    : <form id="check-in-settings-form" onSubmit={save} aria-busy={saving}>
                        <section className="admin-settings-section">
                            <div className="admin-settings-section-heading"><h2>签到奖励</h2>
                                <p>用户在个人中心主动签到领取，积分永久有效。</p></div>
                            <div className="admin-settings-fields">
                                <label htmlFor="check-in-daily">每日签到奖励</label>
                                <div className="admin-settings-input"><input id="check-in-daily" type="number"
                                                                             inputMode="numeric" min="1"
                                                                             max="2147483647" step="1" required
                                                                             disabled={saving} value={daily}
                                                                             onChange={event => {
                                                                                 setDaily(event.target.value);
                                                                                 setSaved(false)
                                                                             }}/><span>积分 / 天</span></div>
                                <label htmlFor="check-in-bonus">连续 7 天额外奖励</label>
                                <div className="admin-settings-input"><input id="check-in-bonus" type="number"
                                                                             inputMode="numeric" min="1"
                                                                             max="2147483647" step="1" required
                                                                             disabled={saving} value={bonus}
                                                                             onChange={event => {
                                                                                 setBonus(event.target.value);
                                                                                 setSaved(false)
                                                                             }}/><span>积分 / 每 7 天</span></div>
                                <p className="admin-settings-help">填写正整数。{valid && <>第 7、14、21…
                                    天合计可领取 {Number(daily) + Number(bonus)} 积分。</>}</p>
                                {error && <p className="admin-error" role="alert">{error}</p>}
                                {saved && <span className="admin-settings-saved" role="status"><Check size={16}
                                                                                                      aria-hidden="true"/>签到配置已保存</span>}
                            </div>
                        </section>
                        <section className="admin-settings-section">
                            <div className="admin-settings-section-heading"><h2>领取规则</h2>
                                <p>北京时间自然日，每个账号每天一次。</p></div>
                            <div className="admin-settings-rules"><p>每天 00:00 重置签到资格。连续签到每满 7
                                天，额外奖励一次；漏签后重新累计，不支持补签。</p><p>配置调整只影响之后领取的奖励，已经发放的积分保留。奖励与基础积分共同用于上传和
                                AI 创作。</p></div>
                        </section>
                    </form>}
        </div>
    </section>
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

  /** 校验当前分组并阻止重复提交，保存不会重置用户已经消耗的积分。 */
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
              <div className="admin-settings-section-heading"><h2 id="quota-settings-title">共享积分</h2><p>统一设置每位用户上传与 AI 创作可共用的免费累计积分。</p></div>
              <div className="admin-settings-fields">
                  <label htmlFor="free-upload-quota">基础免费积分</label>
                <div className="admin-settings-input">
                  <input id="free-upload-quota" type="number" inputMode="numeric" min="0" max="2147483647" step="1" required value={value} disabled={saving}
                    aria-describedby="quota-input-help quota-change-note" onChange={event => { setValue(event.target.value); setSaved(false); setError('') }} />
                  <span>积分 / 用户</span>
                </div>
                  <p id="quota-input-help" className="admin-settings-help">填写 0–2,147,483,647 之间的整数。设为 0
                      不再提供基础积分，用户仍可使用签到所得积分。</p>
                <div className="admin-settings-change" id="quota-change-note" role="status">
                  <span>当前 {settings.freeUploadQuota.toLocaleString()} 积分</span><ArrowRight size={16} aria-hidden="true" /><strong>{valid ? total.toLocaleString() + ' 积分' : '请输入有效积分'}</strong>
                    <p>{valid && total === 0 ? '保存后基础积分为 0，签到已获积分和已有图片均保留。'
                        : changed && total < settings.freeUploadQuota ? '降低积分不会清除已用积分；剩余积分按基础积分、签到收入和已有消耗重新计算。'
                            : '已用积分和签到收入保持不变，剩余积分随新的基础积分重新计算。'}</p>
                </div>
                {error && <p className="admin-error" role="alert">{error}</p>}
                {saved && <span className="admin-settings-saved" role="status"><Check size={16} aria-hidden="true" />配置已保存</span>}
              </div>
            </section>
            <section className="admin-settings-section" aria-labelledby="quota-rules-title">
              <div className="admin-settings-section-heading"><h2 id="quota-rules-title">生效规则</h2><p>调整总额，保留已有消耗。</p></div>
              <div className="admin-settings-rules">
                <p>对新用户和已有用户统一生效，不按天重置。上传每张消耗 1 积分，AI 创作按模型配置计费，删除图片不返还积分。AI 任务提交时预留对应积分，成功保存后扣除，失败释放。</p>
                  <div className="admin-settings-example"><span>例如，用户已消耗 30 积分，尚无签到收入</span>
                      <p>基础积分设为 <b>200</b><ArrowRight size={14} aria-hidden="true"/>剩余 <b>170</b></p>
                      <p>基础积分设为 <b>20</b><ArrowRight size={14} aria-hidden="true"/>剩余 <b>0</b></p></div>
                <p>调高积分后可继续使用新增积分；已经开始的上传会继续完成。</p>
              </div>
            </section>
          </form>}
    </div>
  </section>
}
