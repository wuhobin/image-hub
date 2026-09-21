import { memo, useRef } from 'react'
import type { RefObject } from 'react'
import * as THREE from 'three'
import gsap from 'gsap'
import { useGSAP } from '@gsap/react'

gsap.registerPlugin(useGSAP)

const pointVertex = `
  attribute vec4 aStar;
  attribute vec3 aTint;
  uniform float uTime;
  uniform float uDpr;
  varying float vLight;
  varying float vAngle;
  varying float vAspect;
  varying float vSprite;
  varying float vNear;
  varying vec3 vColor;
  void main() {
    vec4 mv = modelViewMatrix * vec4(position, 1.0);
    gl_Position = projectionMatrix * mv;
    float diameter = clamp(aStar.x * 28.0 / -mv.z, 0.4, 3.6);
    vSprite = diameter * 7.0 + 5.0;
    gl_PointSize = vSprite * uDpr;
    vNear = smoothstep(1.3, 2.2, diameter);
    // A few bright stars twinkle; the distant field stays calm.
    float twinkle = step(0.88, fract(aStar.z * 7.1)) * step(1.0, aStar.y);
    vLight = aStar.y * (1.0 + twinkle * sin(uTime * 0.8 + aStar.z) * 0.22);
    vAngle = aStar.z;
    vAspect = aStar.w;
    vColor = aTint;
  }
`
const pointFragment = `
  varying float vLight;
  varying float vAngle;
  varying float vAspect;
  varying float vSprite;
  varying float vNear;
  varying vec3 vColor;
  void main() {
    vec2 pixel = (gl_PointCoord - 0.5) * vSprite;
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    p = mat2(cos(vAngle), -sin(vAngle), sin(vAngle), cos(vAngle)) * p;
    p *= vec2(inversesqrt(vAspect), sqrt(vAspect));
    float d = dot(p, p);
    float light = exp(-d * 65.0) + exp(-d * 24.0) * 0.16 + exp(-d * 7.0) * 0.008;
    float core = exp(-dot(pixel, pixel) * 0.5) * vNear * 2.5;
    gl_FragColor = vec4((vColor * light + vec3(core)) * vLight, 1.0);
  }
`
// 直接合成星点，不再围绕输入框扭曲、聚光或扩散。
const skyFragment = `
  uniform sampler2D uStars;
  uniform float uHasBox;
  uniform float uReveal;
  varying vec2 vUv;
  void main() {
    vec3 light = texture2D(uStars, vUv).rgb;
    float vignette = 1.0 - smoothstep(0.22, 0.85, distance(vUv, vec2(0.5)));
    float bottomFade = smoothstep(0.0, 0.18, vUv.y);
    gl_FragColor = vec4(light * mix(vignette, 1.0, uHasBox), 1.0);
    vec3 ambient = gl_FragColor.rgb * 1.2;
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
    vec3 base = mix(vec3(0.035, 0.043, 0.063), vec3(0.002, 0.003, 0.005), uHasBox * bottomFade);
    gl_FragColor.rgb = base + mix(ambient, gl_FragColor.rgb, uHasBox) * bottomFade * uReveal;
  }
`

export type StarfieldProps = { target?: RefObject<HTMLDivElement | null> }

export default memo(function Starfield({ target }: StarfieldProps) {
  const hostRef = useRef<HTMLDivElement>(null)

  useGSAP((_context, contextSafe) => {
    // 留出一次页面绘制，并让 StrictMode 的检查先完成，避免重复创建昂贵的 WebGL 上下文。
    // contextSafe 让延后的补间和清理函数仍归属当前组件，切换页面时一并回收。
    const initialize = contextSafe!(() => {
      const host = hostRef.current
      if (!host) return
      let renderer: THREE.WebGLRenderer
      try {
          // Three.js r186 内部创建的上下文固定 alpha:true；显式创建才能保证画布不透明。
          const canvas = document.createElement('canvas')
          const context = canvas.getContext('webgl2', {
              alpha: false, antialias: false, depth: false, stencil: false, powerPreference: 'low-power',
          })
          if (!context) return
          // 星点采用加法混合，背景只有一个平面；无需深度、模板或保留绘图缓冲区。
          renderer = new THREE.WebGLRenderer({canvas, context})
      } catch {
        // Keep the CSS background and all product interactions when WebGL is unavailable.
        return
      }
        // 首帧提交前不把空画布插入页面。
      renderer.toneMapping = THREE.ReinhardToneMapping
      renderer.domElement.setAttribute('aria-hidden', 'true')
      const scene = new THREE.Scene()
      const camera = new THREE.PerspectiveCamera(65, 1, 0.1, 100)
      const geometry = new THREE.BufferGeometry()
      const count = window.innerWidth < 600 ? 3000 : 7000
      const positions = new Float32Array(count * 3)
      const stars = new Float32Array(count * 4)
      const tints = new Float32Array(count * 3)
      for (let i = 0; i < count; i++) {
        positions[i * 3] = (Math.random() - 0.5) * 90
        positions[i * 3 + 1] = (Math.random() - 0.5) * 60
        positions[i * 3 + 2] = -Math.random() * 55 - 6
        stars.set([0.3 + Math.pow(Math.random(), 3) * 1.9, 0.07 + Math.pow(Math.random(), 4) * 2.1, Math.random() * 100, 0.65 + Math.random() * 0.9], i * 4)
        const color = Math.random()
        tints.set(color < 0.65 ? [0.32, 0.63, 1] : color < 0.94 ? [0.85, 0.91, 1] : [1, 0.76, 0.52], i * 3)
      }
      geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))
      geometry.setAttribute('aStar', new THREE.BufferAttribute(stars, 4))
      geometry.setAttribute('aTint', new THREE.BufferAttribute(tints, 3))
      const pointsMaterial = new THREE.ShaderMaterial({
        vertexShader: pointVertex, fragmentShader: pointFragment,
        uniforms: { uTime: { value: 0 }, uDpr: { value: 1 } },
        transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
      })
      const points = new THREE.Points(geometry, pointsMaterial)
      scene.add(points)
      const texture = new THREE.WebGLRenderTarget(1, 1, {
        depthBuffer: false,
        type: renderer.extensions.has('EXT_color_buffer_float') ? THREE.HalfFloatType : THREE.UnsignedByteType,
      })
      const postScene = new THREE.Scene()
      const postCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
      const postGeometry = new THREE.PlaneGeometry(2, 2)
      const material = new THREE.ShaderMaterial({
        vertexShader: 'varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0., 1.); }',
        fragmentShader: skyFragment,
        uniforms: {
            uStars: {value: texture.texture}, uHasBox: {value: target ? 1 : 0}, uReveal: {value: 0},
        },
      })
      postScene.add(new THREE.Mesh(postGeometry, material))
      let quality = 1
      let width = 1
      let height = 1
      let frameDirty = true
      let boundsDirty = true
      let sizeDirty = true
      const resize = () => {
        const nextWidth = Math.max(host.clientWidth, 1)
        const nextHeight = Math.max(host.clientHeight, 1)
        // 同时限制像素密度和总像素数，避免大屏、长列表放大两遍全屏后处理的成本。
        const dpr = Math.min(window.devicePixelRatio, nextWidth < 600 ? 1 : 1.5,
          Math.sqrt(1_000_000 / (nextWidth * nextHeight))) * quality
        const dprChanged = renderer.getPixelRatio() !== dpr
        if (width !== nextWidth || height !== nextHeight || dprChanged) {
          width = nextWidth
          height = nextHeight
          if (dprChanged) renderer.setPixelRatio(dpr)
          renderer.setSize(width, height)
          texture.setSize(Math.round(width * dpr), Math.round(height * dpr))
        }
        // 首页以视口为固定取景范围，内容增高只向下延伸，不缩放或挪动原有星点。
        camera.setViewOffset(width, target ? window.innerHeight : height, 0, 0, width, height)
        pointsMaterial.uniforms.uDpr.value = dpr
        sizeDirty = false
        frameDirty = true
        boundsDirty = true
      }
      const onResize = () => { sizeDirty = true; syncLoop() }
      const observer = new ResizeObserver(onResize)
      observer.observe(host)
      window.addEventListener('resize', onResize)
      const wanted = { x: 0, y: 0, hover: 0 }
      const motion = { ...wanted }
      // Reuse tweens; retarget from the current value instead of reversing an
      // eased timeline (which feels unresponsive when hover changes near its ends).
      const controls = (Object.keys(wanted) as (keyof typeof wanted)[]).map(key => ({
        key,
        destination: Number.NaN,
        to: gsap.quickTo(motion, key, { duration: 0.85, ease: 'power3.out', paused: true }),
      }))
      const surface = host.closest<HTMLElement>('.hero, .creation-page, .auth-story') ?? host.parentElement ?? host
      const uploadSurface = target?.current
      const entrance = { progress: 0 }
      const introMotion = gsap.timeline({ paused: true })
          // 只渐亮星点，画布始终不透明，避免 WebGL 层参与 CSS 透明度动画。
          .to(material.uniforms.uReveal, {value: 1, duration: 1.4, ease: 'sine.out'}, 0)
      // 创作页和上传页保持固定景深，其他页面沿用原有入场效果。
      if (!target) introMotion.to(entrance, { progress: 1, duration: 4.4, ease: 'power3.out' }, 0)
      const hoverMotion = gsap.timeline({ paused: true, defaults: { duration: 1, ease: 'none' } })
      const uploadIcon = uploadSurface?.querySelector('.upload-symbol')
      if (uploadIcon) hoverMotion.to(uploadIcon, { y: -5, scale: 1.06 }, 0)
      let hovered = false
      const enterUpload = (event: PointerEvent) => { if (event.pointerType === 'mouse') hovered = true }
      const leaveUpload = () => { hovered = false }
      uploadSurface?.addEventListener('pointerenter', enterUpload)
      uploadSurface?.addEventListener('pointerleave', leaveUpload)
      uploadSurface?.addEventListener('pointercancel', leaveUpload)
      window.addEventListener('blur', leaveUpload)
      const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
      let hostBounds = host.getBoundingClientRect()
      let pointerEvent: PointerEvent | null = null
      const onPointer = (event: PointerEvent) => {
        if (event.pointerType !== 'mouse' || reducedMotion.matches) return
        pointerEvent = event
      }
      const resetPointer = () => { pointerEvent = null; wanted.x = wanted.y = 0 }
      // 画布和上传框随页面一起滚动，相对坐标不变；仅在下次指针移动时刷新视口坐标。
      const onScroll = () => { boundsDirty = true }
      surface.addEventListener('pointermove', onPointer, { passive: true })
      surface.addEventListener('pointerleave', resetPointer)
      window.addEventListener('blur', resetPointer)
      window.addEventListener('scroll', onScroll, { passive: true })
      let visible = false
      const visibility = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; syncLoop() })
      visibility.observe(host)
      let elapsed = 0
      let renderedStatic = false
      const resetStatic = () => { resetPointer(); renderedStatic = false; frameDirty = true; syncLoop() }
      reducedMotion.addEventListener('change', resetStatic)
      let accumulatedMs = 0
        let frameBudgetMs = 0
      let sampleMs = 0
      let sampleFrames = 0
      const render = (_time: number, deltaMs: number) => {
        if (!visible || document.hidden || failed) return
        const still = reducedMotion.matches
        accumulatedMs += deltaMs
          frameBudgetMs += deltaMs
        // 移动端背景最多 30 帧；只节流装饰，业务交互仍按浏览器正常帧率响应。
        const interval = width < 600 ? 1000 / 30 : 1000 / 60
          if (!still && !sizeDirty && !frameDirty && frameBudgetMs < interval - 1) return
        const delta = Math.min(accumulatedMs / 1000, 0.1)
        accumulatedMs = 0
          // 保留限帧余量，避免计时误差让 60Hz 屏幕频繁漏帧；运动仍按实际经过时间推进。
          frameBudgetMs = Math.max(-1, Math.min(frameBudgetMs - interval, interval))
        // 连续慢帧才降低分辨率，避免一次网络回调或着色器准备影响画质。
        if (!still && deltaMs > 0) {
          sampleMs += deltaMs
          sampleFrames++
          if (sampleFrames >= 45) {
            if (sampleMs / sampleFrames > 40 && quality > 0.65) {
              quality = Math.max(0.65, quality - 0.15)
              sizeDirty = true
            }
            sampleMs = sampleFrames = 0
          }
        }
        // 调整缓冲区会清空画布，必须紧接着绘制，避免 ResizeObserver 留下一帧空白。
        if (sizeDirty) resize()
        if (still && renderedStatic && !frameDirty) return
        if (!still) elapsed += delta
        if (frameDirty) {
          hostBounds = host.getBoundingClientRect()
          boundsDirty = false
          frameDirty = false
        }
        // One clock drives rendering and GSAP, so offscreen also freezes shader tweens.
        introMotion.time(still ? introMotion.duration() : Math.max(introMotion.time(), Math.min(elapsed, introMotion.duration())))
        camera.position.z = target ? 0 : 8 * (1 - entrance.progress)
        if (!still) {
          // Consume only the latest pointer event per frame, with cached bounds.
          if (pointerEvent) {
            if (boundsDirty) {
              hostBounds = host.getBoundingClientRect()
              boundsDirty = false
            }
            wanted.x = THREE.MathUtils.clamp((pointerEvent.clientX - hostBounds.left) / Math.max(hostBounds.width, 1) * 2 - 1, -1, 1)
            wanted.y = THREE.MathUtils.clamp(1 - (pointerEvent.clientY - hostBounds.top) / Math.max(hostBounds.height, 1) * 2, -1, 1)
            pointerEvent = null
          }
          wanted.hover = hovered ? 1 : 0
          for (const control of controls) {
            if (control.destination !== wanted[control.key]) {
              control.destination = wanted[control.key]
              if (control.key === 'hover') control.to.tween.duration(hovered ? 0.85 : 1.1)
              control.to(control.destination).pause()
            }
            // Manually advance reusable tweens so offscreen cannot run ahead.
            control.to.tween.time(control.to.tween.time() + delta)
          }
          camera.position.x = -motion.x * 1.4
          camera.position.y = -motion.y * 0.85
          camera.rotation.y = motion.x * 0.012
          camera.rotation.x = -motion.y * 0.009
          // 持续缓慢游移，鼠标静止时也能看见星空流动；共用时钟，离屏与减少动态效果时自动暂停。
          points.rotation.y = Math.sin(elapsed * 0.055) * 0.18
          points.rotation.z = Math.sin(elapsed * 0.04) * 0.045
        } else if (reducedMotion.matches) {
          camera.position.x = camera.position.y = 0
          camera.rotation.set(0, 0, 0)
        }
        pointsMaterial.uniforms.uTime.value = elapsed
        if (reducedMotion.matches) {
          hoverMotion.time(0)
        } else if (!still) {
          hoverMotion.progress(motion.hover)
        }
        renderer.setClearColor(0x000000)
        renderer.setRenderTarget(texture)
        renderer.render(scene, camera)
        renderer.setRenderTarget(null)
        renderer.render(postScene, postCamera)
          if (!renderer.domElement.isConnected) host.appendChild(renderer.domElement)
        renderedStatic = still
      }
      // 减少动态效果、离屏和后台标签不保留空转 ticker；恢复时先同步几何边界。
      let disposed = false
      let ready = false
      let failed = false
      let running = false
      const syncLoop = () => {
        const drawable = ready && !disposed && !failed && visible && !document.hidden
        const animate = drawable && !reducedMotion.matches
        if (animate && !running) {
            accumulatedMs = frameBudgetMs = 0
          frameDirty = true
          gsap.ticker.add(render)
          running = true
        } else if (!animate && running) {
          gsap.ticker.remove(render)
          running = false
        }
        if (drawable && !animate) render(0, 0)
      }
      document.addEventListener('visibilitychange', syncLoop)
      // 着色器先并行准备，完成后再启动开场时钟，避免首次绘制同步等待编译。
      resize()
      renderer.setRenderTarget(texture)
      const starsReady = renderer.compileAsync(scene, camera)
      renderer.setRenderTarget(null)
      const skyReady = renderer.compileAsync(postScene, postCamera)
      void Promise.all([starsReady, skyReady]).then(() => {
        if (disposed) return
        ready = true
        syncLoop()
      }).catch(() => {
        if (!disposed) host.classList.add('webgl-unavailable')
      })
      const onLost = (event: Event) => {
        event.preventDefault()
        failed = true
        host.classList.add('webgl-unavailable')
        syncLoop()
      }
      renderer.domElement.addEventListener('webglcontextlost', onLost)
      return () => {
        disposed = true
        document.removeEventListener('visibilitychange', syncLoop)
        gsap.ticker.remove(render)
        observer.disconnect()
        window.removeEventListener('resize', onResize)
        visibility.disconnect()
        surface.removeEventListener('pointermove', onPointer)
        surface.removeEventListener('pointerleave', resetPointer)
        window.removeEventListener('blur', resetPointer)
        window.removeEventListener('scroll', onScroll)
        uploadSurface?.removeEventListener('pointerenter', enterUpload)
        uploadSurface?.removeEventListener('pointerleave', leaveUpload)
        uploadSurface?.removeEventListener('pointercancel', leaveUpload)
        window.removeEventListener('blur', leaveUpload)
        reducedMotion.removeEventListener('change', resetStatic)
        renderer.domElement.removeEventListener('webglcontextlost', onLost)
        geometry.dispose()
        pointsMaterial.dispose()
        postGeometry.dispose()
        material.dispose()
        texture.dispose()
        renderer.dispose()
        renderer.domElement.remove()
      }
    })
    let frame = requestAnimationFrame(() => { frame = requestAnimationFrame(initialize) })
    return () => cancelAnimationFrame(frame)
  }, { dependencies: [target], scope: hostRef, revertOnUpdate: true })

  return <div ref={hostRef} className="starfield" aria-hidden="true" />
})
