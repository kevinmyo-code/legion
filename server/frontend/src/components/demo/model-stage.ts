import {
  ACESFilmicToneMapping,
  Box3,
  Color,
  DirectionalLight,
  HemisphereLight,
  Mesh,
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
  /** The browser took the WebGL context away (too many on the page, a GPU reset). */
  onLost?: () => void
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
  dispose(): void
}

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
  controls.enablePan = false
  controls.minDistance = distance * 0.35
  controls.maxDistance = distance * 2.5
  controls.autoRotate = options.autoTurn
  controls.autoRotateSpeed = 1.2
  controls.saveState()

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

  const resize = () => {
    const width = host.clientWidth || 1
    const height = host.clientHeight || 1
    renderer.setSize(width, height, false)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
  }
  const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(resize)
  observer?.observe(host)
  resize()

  const frame = () => {
    controls.update()
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
      if (!visible) frame()
    },
    reset() {
      controls.reset()
      if (!visible) frame()
    },
    dispose() {
      canvas.removeEventListener('webglcontextlost', lost)
      renderer.setAnimationLoop(null)
      observer?.disconnect()
      host.removeEventListener('pointerdown', holdTouch, { capture: true })
      controls.removeEventListener('start', firstTouch)
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
