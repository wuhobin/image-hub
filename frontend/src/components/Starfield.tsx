import { memo, useEffect, useRef } from 'react'
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
const lensFragment = `
  uniform sampler2D uStars;
  uniform vec2 uResolution;
  uniform vec4 uBox;
  uniform float uTime;
  uniform float uEnergy;
  uniform float uExpansion;
  uniform float uHasBox;
  uniform float uRadius;
  uniform float uArrival;
  varying vec2 vUv;

  vec3 sampleStars(vec2 uv) {
    vec2 bounds = min(uv, 1.0 - uv) * uResolution;
    return texture2D(uStars, clamp(uv, 0.0, 1.0)).rgb
      * smoothstep(0.0, 2.0, min(bounds.x, bounds.y));
  }
  void main() {
    vec2 p = vUv * uResolution - uBox.xy;
    vec2 q = abs(p) - uBox.zw + uRadius;
    float edge = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadius;
    float outside = max(edge, 0.0);
    float amount = uHasBox * uArrival;
    float spread = 1.0 + uExpansion * 0.28;
    float mass = 1.0 + uEnergy * 0.3 + uExpansion * 0.35;

    // Point-mass lens equation over a rounded rectangle: stars curve and stretch
    // continuously near the boundary, rather than repeating shifted copies.
    float horizon = max(uBox.w, 24.0);
    // Expand the surrounding lens field while keeping the physical rim fixed.
    float impact = horizon + outside / spread;
    float focus = horizon + 26.0 * mass;
    float source = impact - focus * focus / impact;
    vec2 direction = normalize(p / max(uBox.zw, vec2(1.0)) + vec2(0.00001));
    vec2 bent = (uBox.xy + p * 0.45 + direction * source * 1.8) / uResolution;
    float influence = amount * (1.0 - smoothstep(45.0 * spread, 115.0 * spread, edge));
    vec3 light = sampleStars(mix(vUv, bent, influence));
    light *= mix(1.0, smoothstep(-1.0, 1.0, edge), amount);
    vec2 title = (p - vec2(0.0, uBox.w + 110.0)) / vec2(max(uBox.z * 1.25, 210.0), 200.0);
    float quiet = (1.0 - smoothstep(0.35, 1.0, length(title))) * smoothstep(12.0, 48.0, edge);
    light *= pow(1.0 - quiet * amount * 0.98, 2.0);

    // Integrate starlight only along the subpixel rim; the rest costs one sample.
    float ring = edge - 0.7;
    float pixel = max(fwidth(edge), 0.001);
    float coverage = clamp((ring + 0.3) / pixel + 0.5, 0.0, 1.0)
      - clamp((ring - 0.3) / pixel + 0.5, 0.0, 1.0);
    if (amount > 0.0 && abs(ring) < 3.0) {
      vec2 tangent = vec2(-direction.y, direction.x);
      vec3 compressed = vec3(0.0);
      for (int i = -8; i <= 8; i++) {
        float offset = float(i);
        float weight = exp(-offset * offset / 25.0);
        for (int j = -1; j <= 1; j++) {
          vec2 ray = tangent * offset * 12.0 + direction * float(j) * 18.0 * mass;
          compressed += sampleStars(bent + ray / uResolution) * weight;
        }
      }
      light += compressed * (coverage + exp(-abs(ring) / 0.6) * 0.3) * amount * 3.0;
    }

    float orbit = atan(direction.y, direction.x) + uTime * 0.45;
    float flow = 0.12 + 1.4 * pow(0.5 + 0.32 * sin(orbit * 2.0) + 0.18 * sin(orbit * 5.0), 2.0);
    float flare = max(exp(-outside / (20.0 * spread)) - exp(-5.0), 0.0) * 0.035 * flow;
    light += vec3(0.5, 0.72, 1.0) * flare * amount * smoothstep(-0.5, 0.5, edge);
    float vignette = 1.0 - smoothstep(0.22, 0.85, distance(vUv, vec2(0.5, 0.5)));
    float bottomFade = smoothstep(0.0, 0.18, vUv.y);
    gl_FragColor = vec4(light * mix(vignette, 1.0, uHasBox), 1.0);
    vec3 ambient = gl_FragColor.rgb * 1.2;
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
    vec3 base = mix(vec3(0.035, 0.043, 0.063), vec3(0.002, 0.003, 0.005), uHasBox * bottomFade);
    gl_FragColor.rgb = base
      + mix(ambient, gl_FragColor.rgb, uHasBox) * bottomFade;
  }
`

type Props = { target?: RefObject<HTMLDivElement | null>; active?: boolean; paused: boolean }

export default memo(function Starfield({ target, active = false, paused }: Props) {
  const hostRef = useRef<HTMLDivElement>(null)
  const activeRef = useRef(active)
  const pausedRef = useRef(paused)
  useEffect(() => { activeRef.current = active }, [active])
  useEffect(() => { pausedRef.current = paused }, [paused])

  useGSAP(() => {
    const host = hostRef.current
    if (!host) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: false, alpha: false, powerPreference: 'low-power' })
    } catch {
      // Keep the CSS background and all product interactions when WebGL is unavailable.
      return
    }
    host.appendChild(renderer.domElement)
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
      fragmentShader: lensFragment,
      uniforms: {
        uStars: { value: texture.texture }, uResolution: { value: new THREE.Vector2(1, 1) },
        uBox: { value: new THREE.Vector4(0, 0, 1, 1) }, uTime: { value: 0 },
        uEnergy: { value: 0 }, uHasBox: { value: target ? 1 : 0 },
        uExpansion: { value: 0 },
        uRadius: { value: 0 }, uArrival: { value: 0 },
      },
    })
    postScene.add(new THREE.Mesh(postGeometry, material))
    let width = 1
    let height = 1
    let boxDirty = true
    const resize = () => {
      width = host.clientWidth
      height = host.clientHeight
      const dpr = Math.min(window.devicePixelRatio, width < 600 ? 1.25 : 1.5)
      renderer.setPixelRatio(dpr)
      renderer.setSize(width, height)
      texture.setSize(Math.round(width * dpr), Math.round(height * dpr))
      camera.aspect = width / height
      camera.updateProjectionMatrix()
      pointsMaterial.uniforms.uDpr.value = dpr
      material.uniforms.uResolution.value.set(width, height)
      boxDirty = true
    }
    const observer = new ResizeObserver(resize)
    observer.observe(host)
    if (target?.current) observer.observe(target.current)
    const wanted = { x: 0, y: 0, expansion: 0, energy: 0 }
    const motion = { ...wanted }
    // Reuse four tweens; retarget from the current value instead of reversing an
    // eased timeline (which feels unresponsive when hover changes near its ends).
    const controls = (Object.keys(wanted) as (keyof typeof wanted)[]).map(key => ({
      key,
      destination: Number.NaN,
      to: gsap.quickTo(motion, key, { duration: 0.85, ease: 'power3.out', paused: true }),
    }))
    const surface = host.parentElement ?? host
    const uploadSurface = target?.current
    const entrance = { progress: 0 }
    const introMotion = gsap.timeline({ paused: true })
      .to(entrance, { progress: 1, duration: 4.4, ease: 'power3.out' }, 0)
      .to(material.uniforms.uArrival, { value: 1, duration: 1.7, ease: 'power2.inOut' }, 1.1)
      .from(renderer.domElement, { autoAlpha: 0, duration: 1.4, ease: 'sine.out' }, 0)
    const hoverMotion = gsap.timeline({ paused: true, defaults: { duration: 1, ease: 'none' } })
      .to(material.uniforms.uExpansion, { value: 1 }, 0)
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
      if (event.pointerType !== 'mouse' || pausedRef.current || reducedMotion.matches) return
      pointerEvent = event
    }
    const resetPointer = () => { pointerEvent = null; wanted.x = wanted.y = 0 }
    const onScroll = () => { boxDirty = true }
    surface.addEventListener('pointermove', onPointer, { passive: true })
    surface.addEventListener('pointerleave', resetPointer)
    window.addEventListener('blur', resetPointer)
    window.addEventListener('scroll', onScroll, { passive: true, capture: true })
    let visible = true
    const visibility = new IntersectionObserver(([entry]) => { visible = entry.isIntersecting })
    visibility.observe(host)
    let elapsed = 0
    let renderedStatic = false
    const resetStatic = () => { resetPointer(); renderedStatic = false; boxDirty = true }
    reducedMotion.addEventListener('change', resetStatic)
    const render = (_time: number, deltaMs: number) => {
      const delta = Math.min(deltaMs / 1000, 0.05)
      if (!visible || document.hidden) return
      const still = reducedMotion.matches || pausedRef.current
      if (still && renderedStatic && !boxDirty) return
      if (!still) elapsed += delta
      if (boxDirty) {
        hostBounds = host.getBoundingClientRect()
        if (target?.current) {
          const rect = target.current.getBoundingClientRect()
          material.uniforms.uBox.value.set(
            rect.left - hostBounds.left + rect.width / 2,
            height - (rect.top - hostBounds.top + rect.height / 2),
            rect.width / 2, rect.height / 2,
          )
          material.uniforms.uRadius.value = parseFloat(getComputedStyle(target.current).borderTopLeftRadius) || 0
        }
        boxDirty = false
      }
      // One clock drives rendering and GSAP, so offscreen/pause also freezes shader tweens.
      introMotion.time(still ? introMotion.duration() : Math.max(introMotion.time(), Math.min(elapsed, introMotion.duration())))
      camera.position.z = 8 * (1 - entrance.progress)
      if (!still) {
        // Consume only the latest pointer event per frame, with cached bounds.
        if (pointerEvent) {
          wanted.x = THREE.MathUtils.clamp((pointerEvent.clientX - hostBounds.left) / Math.max(hostBounds.width, 1) * 2 - 1, -1, 1)
          wanted.y = THREE.MathUtils.clamp(1 - (pointerEvent.clientY - hostBounds.top) / Math.max(hostBounds.height, 1) * 2, -1, 1)
          pointerEvent = null
        }
        wanted.expansion = hovered ? 1 : 0
        wanted.energy = activeRef.current ? 1 : 0
        for (const control of controls) {
          if (control.destination !== wanted[control.key]) {
            control.destination = wanted[control.key]
            if (control.key === 'expansion') control.to.tween.duration(hovered ? 0.85 : 1.1)
            control.to(control.destination).pause()
          }
          // Manually advance reusable tweens so pause/offscreen cannot run ahead.
          control.to.tween.time(control.to.tween.time() + delta)
        }
        camera.position.x = -motion.x * 1.4
        camera.position.y = -motion.y * 0.85
        camera.rotation.y = motion.x * 0.012
        camera.rotation.x = -motion.y * 0.009
        points.rotation.y = Math.sin(elapsed * 0.018) * 0.045
        points.rotation.z = Math.sin(elapsed * 0.025) * 0.008
      } else if (reducedMotion.matches) {
        camera.position.x = camera.position.y = 0
        camera.rotation.set(0, 0, 0)
      }
      pointsMaterial.uniforms.uTime.value = elapsed
      material.uniforms.uTime.value = elapsed
      material.uniforms.uEnergy.value = reducedMotion.matches ? 0 : motion.energy
      if (reducedMotion.matches) {
        hoverMotion.time(0)
      } else if (!still) {
        hoverMotion.progress(motion.expansion)
      }
      renderer.setClearColor(0x000000)
      renderer.setRenderTarget(texture)
      renderer.render(scene, camera)
      renderer.setRenderTarget(null)
      renderer.render(postScene, postCamera)
      renderedStatic = still
    }
    resize()
    gsap.ticker.add(render)
    const onLost = (event: Event) => { event.preventDefault(); host.classList.add('webgl-unavailable') }
    renderer.domElement.addEventListener('webglcontextlost', onLost)
    return () => {
      gsap.ticker.remove(render)
      observer.disconnect()
      visibility.disconnect()
      surface.removeEventListener('pointermove', onPointer)
      surface.removeEventListener('pointerleave', resetPointer)
      window.removeEventListener('blur', resetPointer)
      window.removeEventListener('scroll', onScroll, true)
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
  }, { dependencies: [target], scope: hostRef, revertOnUpdate: true })

  return <div ref={hostRef} className="starfield" aria-hidden="true" />
})
