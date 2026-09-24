import {useState} from 'react'
import {generationResolution, generationSizeForRatio, generationSizesForModel, imageAspectRatio} from '../../lib/rules'
import {AdminSelect} from './AdminSelect'

/** 先定位比例，再配置该比例的分辨率；切换比例仅切换视图，不覆盖其他比例的选择。 */
export function ModelSizeFields({modelCode, sizes, defaultSize, existingSizes, disabled, onChange, onDefaultChange}: {
    modelCode: string
    sizes: string[]
    defaultSize: string
    existingSizes: string[]
    disabled: boolean
    onChange: (sizes: string[]) => void
    onDefaultChange: (size: string) => void
}) {
    const presets = generationSizesForModel(modelCode)
    const ratios = [...new Set(presets.map(imageAspectRatio))]
    const [activeRatio, setActiveRatio] = useState(() => imageAspectRatio(defaultSize))
    const ratio = ratios.includes(activeRatio) ? activeRatio : ratios[0]
    const candidates = presets.filter(size => imageAspectRatio(size) === ratio)
    const selected = candidates.filter(size => sizes.includes(size))
    // 从原配置保留候选项，取消勾选后仍能重新选回；旧模型尺寸不混入当前模型的档位。
    const custom = [...new Set([...existingSizes, ...sizes])].filter(size => !presets.includes(size))
    const defaultRatios = [...new Set(sizes.map(imageAspectRatio))]
    const defaultRatio = imageAspectRatio(defaultSize)

    function toggle(size: string, checked: boolean) {
        onChange(checked ? [...sizes, size] : sizes.filter(item => item !== size))
    }

    return <section className="model-size-settings" aria-labelledby="model-sizes-title">
        <div className="model-size-heading">
            <div><h4 id="model-sizes-title">允许的尺寸</h4><p id="model-sizes-summary"
                                                              role="status">已开放 {defaultRatios.length} 种比例
                · {sizes.length} 种尺寸</p></div>
            <div className="model-size-actions">
                <button id="model-sizes-presets" type="button" className="quiet-link" disabled={disabled}
                        onClick={() => onChange([...presets])}>使用全部预设
                </button>
                <button id="model-sizes-clear" type="button" className="quiet-link" disabled={disabled || !sizes.length}
                        onClick={() => onChange([])}>清空全部
                </button>
            </div>
        </div>
        <div className="model-size-steps">
            <div className="model-size-ratio">
                <label htmlFor="model-size-ratio">画面比例</label>
                <AdminSelect id="model-size-ratio" label="画面比例" value={ratio} disabled={disabled}
                             onChange={setActiveRatio}
                             options={ratios.map(value => ({
                                 value,
                                 label: value,
                                 description: '已选 ' + presets.filter(size => imageAspectRatio(size) === value && sizes.includes(size)).length + ' 档'
                             }))}/>
                <p>切换比例，分别设置可用档位。</p>
            </div>
            <div className="model-size-resolutions">
                <div className="model-size-tier-heading"><span
                    id="model-size-tiers-label">{ratio} 的分辨率 <small>可多选</small></span>
                    <div className="model-size-actions">
                        <button id="model-ratio-all" type="button" className="quiet-link"
                                disabled={disabled || selected.length === candidates.length}
                                onClick={() => onChange([...new Set([...sizes, ...candidates])])}>全选
                        </button>
                        <button id="model-ratio-clear" type="button" className="quiet-link"
                                disabled={disabled || !selected.length}
                                onClick={() => onChange(sizes.filter(size => !candidates.includes(size)))}>清空
                        </button>
                    </div>
                </div>
                <div className="model-size-tiers" role="group" aria-labelledby="model-size-tiers-label">
                    {candidates.map(size => <label className="model-size-tier" key={size}>
                        <input type="checkbox" value={size} checked={sizes.includes(size)} disabled={disabled}
                               onChange={event => toggle(size, event.target.checked)}
                               aria-label={ratio + ' · ' + generationResolution(size)}/>
                        <span>{generationResolution(size)}</span><small>{size.replace('x', ' × ')}</small>
                    </label>)}
                </div>
            </div>
        </div>
        {custom.length > 0 && <details className="model-size-legacy">
            <summary>已有其他尺寸 <span>{custom.filter(size => sizes.includes(size)).length} / {custom.length}</span>
            </summary>
            <p>这些尺寸不属于当前模型预设，原选择已保留。使用全部预设会替换这些选择。</p>
            <AdminSelect id="model-custom-sizes" label="已有其他尺寸" multiple
                         value={sizes.filter(size => custom.includes(size))} disabled={disabled}
                         options={custom.map(value => ({
                             value,
                             label: imageAspectRatio(value),
                             description: value.replace('x', ' × ') + ' px'
                         }))}
                         onChange={values => onChange([...sizes.filter(size => !custom.includes(size)), ...values])}/>
        </details>}
        <div className="model-form-grid model-size-defaults">
            <div><label htmlFor="model-default-ratio">默认比例</label><AdminSelect id="model-default-ratio"
                                                                                   label="默认比例" value={defaultRatio}
                                                                                   disabled={disabled}
                                                                                   options={defaultRatios.map(value => ({
                                                                                       value,
                                                                                       label: value
                                                                                   }))}
                                                                                   onChange={value => onDefaultChange(generationSizeForRatio(sizes, value, defaultSize))}/>
            </div>
            <div><label htmlFor="model-default-size">默认分辨率</label><AdminSelect id="model-default-size"
                                                                                    label="默认分辨率"
                                                                                    value={defaultSize}
                                                                                    disabled={disabled}
                                                                                    options={sizes.filter(size => imageAspectRatio(size) === defaultRatio).map(value => ({
                                                                                        value,
                                                                                        label: presets.includes(value) ? generationResolution(value) : '已有尺寸',
                                                                                        description: value.replace('x', ' × ') + ' px'
                                                                                    }))}
                                                                                    onChange={onDefaultChange}/></div>
        </div>
    </section>
}
