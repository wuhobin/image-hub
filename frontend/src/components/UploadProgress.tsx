import { useRef } from 'react'
import gsap from 'gsap'
import { useGSAP } from '@gsap/react'

gsap.registerPlugin(useGSAP)

export default function UploadProgress({ value, label }: { value: number; label: string }) {
  const panel = useRef<HTMLDivElement>(null)
  const bar = useRef<HTMLProgressElement>(null)
  const percentage = useRef<HTMLSpanElement>(null)
  const moveProgress = useRef<gsap.QuickToFunc | null>(null)

  useGSAP(() => {
    const element = bar.current!
    const text = percentage.current!
    // 复用同一补间，新进度从当前显示值继续；不逐帧触发 React 和星空重绘。
    moveProgress.current = gsap.quickTo(element, 'value', {
      duration: 0.65,
      ease: 'power2.out',
      onUpdate: () => { text.textContent = `${Math.floor(element.value)}%` },
    })
    return () => { moveProgress.current = null }
  }, { scope: panel })

  useGSAP(() => {
    const move = moveProgress.current!
    move(value)
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) move.tween.progress(1).pause()
  }, { dependencies: [value], scope: panel })

  return <div className="upload-progress-panel" ref={panel}>
    <div className="upload-progress-heading"><span>{label}</span><span ref={percentage} aria-hidden="true">0%</span></div>
    <progress ref={bar} className="upload-progress" value={0} max={100} aria-label="图片上传总进度" />
  </div>
}
