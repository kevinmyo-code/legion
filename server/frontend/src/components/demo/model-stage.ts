import {
  ACESFilmicToneMapping,
  Box3,
  Color,
  DirectionalLight,
  HemisphereLight,
  Mesh,
  MeshStandardMaterial,
  PerspectiveCamera,
  PMREMGenerator,
  Scene,
  SRGBColorSpace,
  Vector3,
  WebGLRenderer,
  type Material,
  type Object3D,
} from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'

/**
 * The 3D half of a /try model viewer, in its own lazy chunk (`model-viewer.tsx` imports it only
 * when the section nears the screen): three.js, its glTF loader and orbit controls. No Draco and
 * no meshopt decoder, so nothing here needs WebAssembly; models are plain or quantized
 * (`KHR_mesh_quantization`, which three reads natively).
 *
 * The scroll rule lives here because the controls own the pointer: until `setActive(true)` the
 * wheel and one-finger touch belong to the page (the canvas says `touch-action: pan-y` and the
 * controls never see a touch), while a mouse drag turns the model at once, since a drag never
 * scrolls a page. Active, every gesture over the model is the model's.
 */

/** The page's paper (`--rc-paper` in `index.css`), so the canvas and its poster are one picture. */
export const STAGE_BACKGROUND = '#faf8f3'
/** Where the camera starts: a three-quarter view from above, the same view the posters show. */
const VIEW_DIRECTION = new Vector3(1, 0.75, 1.25).normalize()
const FOV = 35
/** One arrow-key press turns the model this far (radians). */
export const KEY_TURN = Math.PI / 16

export interface StageOptions {
  /** Turn slowly until the first touch. False under `prefers-reduced-motion`. */
  autoTurn: boolean
  /**
   * The /try scroll story: turn with the page's scroll instead (one and a half turns from entering
   * the screen to leaving it), until the first touch. Read once a frame from where the viewer
   * sits on screen, in the draw loop that runs only while it is seen: no scroll listener.
   */
  scrollTurn?: boolean
  /** The browser took the WebGL context away (too many on the page, a GPU reset). */
  onLost?: () => void
  /**
   * The garage card (`show_part`): two fingers or a right drag slide the view, and the camera may
   * come close enough to read one nut. Off for /try, whose viewers stay as they were.
   */
  explore?: boolean
}

/** A camera view in the model file's own coordinates (metres), as the engine's card gives it. */
export interface StageView {
  target: readonly number[]
  from: readonly number[]
}

export interface Stage {
  /** Turns, zooms and the wheel are the model's (true) or the page's (false). */
  setActive(active: boolean): void
  /** Draw only while the viewer is on screen. */
  setVisible(visible: boolean): void
  /** Turn left/right (dx) or up/down (dy) by whole key presses. */
  turn(dx: number, dy: number): void
  /** Back to the starting view. */
  reset(): void
  /** Fly the camera to `view` over `ms` (0: at once). For models whose nodes carry ids. */
  focus(view: StageView, ms: number): void
  /**
   * Light up the nodes whose `extras.legion.id` is in `ids`: a part tinted, a fastener's marker
   * enlarged and drawn on top; every other part faded. An empty list puts everything back.
   */
  highlight(ids: readonly string[]): void
  dispose(): void
}

/** What a node's glTF `extras.legion` says (`server/garage/glb.py`); three hands it over as `userData`. */
interface Tag {
  id: string
  kind: 'part' | 'fastener' | 'model'
  part?: string
}

const tagOf = (node: Object3D): Tag | undefined => {
  const tag = (node.userData as { legion?: Tag } | undefined)?.legion
  return tag && typeof tag.id === 'string' ? tag : undefined
}

const LIT = 0x8a4a18
const FADED = 0.15

/**
 * True when this browser can draw WebGL at all; asked before the chunk's work starts. The probe's
 * context is released at once: browsers cap live contexts (about 16) and drop the oldest, which
 * could be a viewer on screen.
 */
export function webglAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas')
    const gl = canvas.getContext('webgl2') ?? canvas.getContext('webgl')
    gl?.getExtension('WEBGL_lose_context')?.loseContext()
    return Boolean(gl)
  } catch {
    return false
  }
}

/** The viewer's box is 4:3 (`.rc-model-box`); the starting distance is fitted to it. */
const STAGE_ASPECT = 4 / 3
/** How much of the frame the model may fill at the start. */
const FILL = 0.9

/**
 * How far back along `VIEW_DIRECTION` the camera must stand for every corner of `box` (centred
 * on the origin) to be inside the frame. A bounding sphere is too loose for a flat board and too
 * tight for a tall lever; the corners fit both.
 */
function fitDistance(box: Box3, aspect: number): number {
  const forward = VIEW_DIRECTION
  const right = new Vector3().crossVectors(new Vector3(0, 1, 0), forward).normalize()
  const up = new Vector3().crossVectors(forward, right)
  const tanV = Math.tan(((FOV / 2) * Math.PI) / 180) * FILL
  const tanH = tanV * aspect
  let distance = 1e-3
  for (const x of [box.min.x, box.max.x]) {
    for (const y of [box.min.y, box.max.y]) {
      for (const z of [box.min.z, box.max.z]) {
        const corner = new Vector3(x, y, z)
        const depth = corner.dot(forward)
        distance = Math.max(
          distance,
          depth + Math.abs(corner.dot(right)) / tanH,
          depth + Math.abs(corner.dot(up)) / tanV,
        )
      }
    }
  }
  return distance
}

function disposeTree(root: Object3D) {
  root.traverse((node) => {
    if (!(node instanceof Mesh)) return
    node.geometry.dispose()
    const materials: Material[] = Array.isArray(node.material) ? node.material : [node.material]
    for (const material of materials) {
      for (const value of Object.values(material)) {
        if (value && typeof value === 'object' && 'isTexture' in value) (value as { dispose(): void }).dispose()
      }
      material.dispose()
    }
  })
}

/**
 * Loads `url` (same-origin, with the visit's cookie) into a canvas inside `host` and resolves
 * once the first frame is drawn. Rejects if WebGL is missing or the file does not load; the
 * caller keeps its poster then.
 */
export async function mountStage(host: HTMLElement, url: string, options: StageOptions): Promise<Stage> {
  if (!webglAvailable()) throw new Error('WebGL is not available')
  const gltf = await new GLTFLoader().loadAsync(url)

  const model = gltf.scene
  const renderer = new WebGLRenderer({ antialias: true, powerPreference: 'low-power' })
  try {
    return build(host, renderer, model, options)
  } catch (error) {
    // Half-built: free what exists (the context first) before the caller shows its poster.
    disposeTree(model)
    renderer.forceContextLoss()
    renderer.dispose()
    throw error
  }
}

function build(host: HTMLElement, renderer: WebGLRenderer, model: Object3D, options: StageOptions): Stage {
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.outputColorSpace = SRGBColorSpace
  renderer.toneMapping = ACESFilmicToneMapping
  renderer.setClearColor(STAGE_BACKGROUND, 1)
  const canvas = renderer.domElement
  canvas.className = 'rc-model-canvas'
  canvas.setAttribute('aria-hidden', 'true')

  const scene = new Scene()
  scene.background = new Color(STAGE_BACKGROUND)
  const pmrem = new PMREMGenerator(renderer)
  const room = new RoomEnvironment()
  const environment = pmrem.fromScene(room, 0.04).texture
  pmrem.dispose()
  disposeTree(room)
  scene.environment = environment
  scene.add(new HemisphereLight(0xffffff, 0x8a8170, 0.6))
  const key = new DirectionalLight(0xffffff, 1.2)
  key.position.set(3, 5, 4)
  scene.add(key)

  // Centre the model on the origin and stand the camera far enough back to see all of it.
  const box = new Box3().setFromObject(model)
  const centre = box.getCenter(new Vector3())
  model.position.sub(centre)
  scene.add(model)
  const distance = fitDistance(box.translate(centre.clone().negate()), STAGE_ASPECT)

  const camera = new PerspectiveCamera(FOV, STAGE_ASPECT, distance / 100, distance * 10)
  camera.position.copy(VIEW_DIRECTION).multiplyScalar(distance)
  camera.lookAt(0, 0, 0)

  const controls = new OrbitControls(camera, canvas)
  controls.enableDamping = true
  controls.enablePan = Boolean(options.explore)
  controls.minDistance = distance * (options.explore ? 0.05 : 0.35)
  controls.maxDistance = distance * 2.5
  controls.autoRotate = options.autoTurn && !options.scrollTurn
  controls.autoRotateSpeed = 1.2
  controls.saveState()
  const startOffset = camera.position.clone().sub(controls.target)
  const UP = new Vector3(0, 1, 0)

  // The id-carrying meshes (garage models; none on /try), each with its own material so one can be
  // lit or faded alone (the glTF shares one material across every fastener marker).
  const tagged = new Map<string, { mesh: Mesh; tag: Tag; opacity: number; transparent: boolean }>()
  model.traverse((node) => {
    const tag = tagOf(node)
    if (!tag || !(node instanceof Mesh) || Array.isArray(node.material)) return
    node.material = node.material.clone()
    tagged.set(tag.id, { mesh: node, tag, opacity: node.material.opacity, transparent: node.material.transparent })
  })
  // A camera flight (`focus`): eased from where it is to the view, stepped in the draw loop.
  let flight: { fromPos: Vector3; fromTarget: Vector3; toPos: Vector3; toTarget: Vector3; start: number; ms: number } | null =
    null

  // Drawn only when something changed (review 2026-11-20, frames at 1440 px): the controls moved,
  // the size changed, or the page's scroll turned it.
  let dirty = true
  const redraw = () => {
    dirty = true
  }
  controls.addEventListener('change', redraw)

  let touched = false
  const firstTouch = () => {
    if (touched) return
    touched = true
    controls.autoRotate = false
  }
  controls.addEventListener('start', firstTouch)

  let active = false
  // Inactive, a touch never reaches the controls (captured and stopped on the host, before the
  // canvas's own listener) and the browser keeps vertical panning for the page.
  const holdTouch = (event: PointerEvent) => {
    if (!active && event.pointerType !== 'mouse') event.stopPropagation()
  }
  host.addEventListener('pointerdown', holdTouch, { capture: true })
  const applyActive = () => {
    controls.enableZoom = active
    canvas.style.touchAction = active ? 'none' : 'pan-y'
  }
  applyActive()

  let drawnWidth = 0
  let drawnHeight = 0
  const resize = () => {
    const width = host.clientWidth || 1
    const height = host.clientHeight || 1
    measure()
    // A new buffer only for a new size: setting the same size again clears the canvas, and the
    // model would blank for a frame (review recheck 2026-11-20).
    if (width === drawnWidth && height === drawnHeight) return
    drawnWidth = width
    drawnHeight = height
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    dirty = true
  }
  // Where the viewer sits on the page, read only when a size changes (here or on the page), never
  // in the draw loop: each frame then reads only the scroll position.
  let pageTop = 0
  let boxHeight = 1
  const measure = () => {
    const box = host.getBoundingClientRect()
    pageTop = box.top + window.scrollY
    boxHeight = box.height
  }
  const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize)
  observer?.observe(host)
  // The page changing height above the viewer (a dock's room, a late image) moves where it sits:
  // a lighter watcher that only measures again.
  const pageObserver = options.scrollTurn && typeof ResizeObserver !== 'undefined' ? new ResizeObserver(measure) : null
  pageObserver?.observe(document.documentElement)
  resize()

  let lastThrough = -1
  const frame = () => {
    if (options.scrollTurn && !touched) {
      // How far through the screen the viewer is: 0 as it enters at the foot, 1 as it leaves.
      const top = pageTop - window.scrollY
      const span = window.innerHeight + boxHeight
      const through = span > 0 ? Math.min(1, Math.max(0, (window.innerHeight - top) / span)) : 0
      if (Math.abs(through - lastThrough) > 1e-4) {
        lastThrough = through
        camera.position.copy(controls.target).add(startOffset.clone().applyAxisAngle(UP, through * Math.PI * 3))
        camera.lookAt(controls.target)
        dirty = true
      }
    }
    if (flight) {
      const k = flight.ms > 0 ? Math.min(1, (performance.now() - flight.start) / flight.ms) : 1
      const eased = k * k * (3 - 2 * k)
      camera.position.lerpVectors(flight.fromPos, flight.toPos, eased)
      controls.target.lerpVectors(flight.fromTarget, flight.toTarget, eased)
      if (k >= 1) flight = null
      dirty = true
    }
    // True while it moves (damping, the slow turn): draw those frames too.
    if (controls.update()) dirty = true
    if (!dirty) return
    dirty = false
    renderer.render(scene, camera)
  }
  const lost = (event: Event) => {
    event.preventDefault()
    options.onLost?.()
  }
  canvas.addEventListener('webglcontextlost', lost)
  host.appendChild(canvas)
  frame()
  let visible = true
  renderer.setAnimationLoop(frame)

  return {
    setActive(next) {
      active = next
      // A tap that hands the model the gestures is its first touch: stop the slow turn.
      if (next) firstTouch()
      applyActive()
    },
    setVisible(next) {
      if (next === visible) return
      visible = next
      dirty = true
      renderer.setAnimationLoop(next ? frame : null)
    },
    turn(dx, dy) {
      firstTouch()
      // Orbit by moving the camera around the target: OrbitControls keeps no public rotate call.
      const offset = camera.position.clone().sub(controls.target)
      offset.applyAxisAngle(new Vector3(0, 1, 0), -dx * KEY_TURN)
      const side = new Vector3().crossVectors(new Vector3(0, 1, 0), offset).normalize()
      const tilted = offset.clone().applyAxisAngle(side, dy * KEY_TURN)
      // Never over the top: keep a little off the poles so "up" stays up.
      const elevation = Math.abs(tilted.clone().normalize().y)
      camera.position.copy(controls.target).add(elevation < 0.97 ? tilted : offset)
      camera.lookAt(controls.target)
      dirty = true
      if (!visible) frame()
    },
    reset() {
      controls.reset()
      dirty = true
      if (!visible) frame()
    },
    focus(view, ms) {
      firstTouch()
      // The model was moved to centre it: its own coordinates move with it.
      const shift = model.position
      flight = {
        fromPos: camera.position.clone(),
        fromTarget: controls.target.clone(),
        toPos: new Vector3(view.from[0], view.from[1], view.from[2]).add(shift),
        toTarget: new Vector3(view.target[0], view.target[1], view.target[2]).add(shift),
        start: performance.now(),
        ms,
      }
      dirty = true
      if (!visible) frame()
    },
    highlight(ids) {
      const lit = new Set(ids)
      const litParts = new Set(ids.filter((id) => tagged.get(id)?.tag.kind === 'part'))
      const any = lit.size > 0
      for (const [id, { mesh, tag, opacity, transparent }] of tagged) {
        const material = mesh.material as MeshStandardMaterial
        const hot = lit.has(id)
        const mine = tag.kind === 'fastener' && tag.part !== undefined && litParts.has(tag.part)
        const fade = any && !hot && !mine
        if (tag.kind === 'fastener') {
          mesh.scale.setScalar(hot ? 2.2 : mine ? 1.4 : 1)
          // The nut asked about shows through whatever is in front of it.
          material.depthTest = !hot
          mesh.renderOrder = hot ? 3 : 0
          material.emissive.setHex(hot ? 0xffffff : 0x000000)
          material.emissiveIntensity = hot ? 0.5 : 1
        } else {
          material.emissive.setHex(hot ? LIT : 0x000000)
        }
        material.transparent = fade || transparent
        material.opacity = fade ? Math.min(opacity, FADED) : opacity
        material.depthWrite = !material.transparent
        material.needsUpdate = true
      }
      dirty = true
      if (!visible) frame()
    },
    dispose() {
      canvas.removeEventListener('webglcontextlost', lost)
      renderer.setAnimationLoop(null)
      observer?.disconnect()
      pageObserver?.disconnect()
      host.removeEventListener('pointerdown', holdTouch, { capture: true })
      controls.removeEventListener('start', firstTouch)
      controls.removeEventListener('change', redraw)
      controls.dispose()
      disposeTree(model)
      environment.dispose()
      // Hand the context back now rather than at garbage collection (the browser's cap).
      renderer.forceContextLoss()
      renderer.dispose()
      canvas.remove()
    },
  }
}
