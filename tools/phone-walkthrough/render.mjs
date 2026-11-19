#!/usr/bin/env node
/**
 * Renders the LEGION phone video from a spec (scenes as data) and a folder of recorded footage.
 *
 *   node tools/phone-walkthrough/render.mjs --spec <spec.json> --clips <dir> --out <dir>
 *        [--layout wide|tall|both] [--ffmpeg <dir with ffmpeg and ffprobe>] [--from 0 --to 12]
 *        [--asker <dir of asker WAVs, default <clips>/asker>]
 *        [--check overlay]   (only the money-overlay check frames, no browser)
 *
 * `<clips>/<slot>.json` (all optional): `asks` [{at, line, mute?}] (a `line` the spec does not
 * name, such as "tap", is the recorder's action and makes no sound), `room_wav` and
 * `room_starts_before_video_s`, `home_spans` [[a, b]], `no_overlay_spans` [[a, b]],
 * `overlay: false`. Times are seconds into that slot's mp4.
 *
 * Pipeline, deterministic end to end:
 *  1. `scenes.html` draws every frame from a time value in headless Chromium (Playwright from
 *     server/frontend/node_modules); each frame is screenshot and piped to ffmpeg at 30 fps.
 *  2. ffmpeg lays each slot's footage (`<clips>/<slot>.mp4`, made-up money figures painted over
 *     first) into the phone screen rectangle the page reports, for the scene's in/out, and builds
 *     the sound: Alfred from the take (its room recording, or the mp4's own track) and the
 *     stock-voice asker WAV for each ask at its time. No narration, no music.
 *  3. Outputs per layout: `<name>.mp4` (H.264 + AAC, yuv420p, faststart); plus, once:
 *     `<name>.vtt` (asker lines and `<clips>/<slot>.vtt` cues shifted into place),
 *     `chapters.json` ([{title, line, at}] for every beat and step), `poster.png`, and contact
 *     sheets (`sheet-NN.png`, one frame per second of the wide render).
 *  A slot with no footage renders a labelled placeholder, so pacing can be judged first.
 *  `tall` (1080x1350) burns the captions in: a feed plays it muted.
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const REPO = path.resolve(HERE, '..', '..')
const FRONTEND = path.join(REPO, 'server', 'frontend')
const FPS = 30
const SIZES = { wide: [1920, 1080], tall: [1080, 1350] }

function args() {
  const out = { layout: 'both', name: 'legion-phone' }
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i += 2) out[argv[i].replace(/^--/, '')] = argv[i + 1]
  for (const need of ['spec', 'clips', 'out']) if (!out[need]) throw new Error(`--${need} is required`)
  return out
}

const opt = args()
const bin = (name) => (opt.ffmpeg ? path.join(opt.ffmpeg, name) : name)
const spec = JSON.parse(readFileSync(opt.spec, 'utf8'))
const clipsDir = path.resolve(opt.clips)
const outDir = path.resolve(opt.out)
mkdirSync(outDir, { recursive: true })

function run(cmd, argv) {
  const r = spawnSync(bin(cmd), argv, { encoding: 'utf8', maxBuffer: 1 << 26 })
  if (r.status !== 0) throw new Error(`${cmd} failed: ${r.stderr.slice(-2000)}`)
  return r.stdout
}
function duration(file) {
  return Number(run('ffprobe', ['-v', 'error', '-show_entries', 'format=duration', '-of', 'csv=p=0', file]).trim())
}
function hasAudio(file) {
  return run('ffprobe', ['-v', 'error', '-select_streams', 'a', '-show_entries', 'stream=index', '-of', 'csv=p=0', file]).trim() !== ''
}

// What footage exists, and the asker lines logged for it.
const slots = new Set(
  existsSync(clipsDir) ? readdirSync(clipsDir).filter((f) => f.endsWith('.mp4')).map((f) => f.slice(0, -4)) : [],
)
function asksOf(slot) {
  return slotMeta(slot).asks || []
}
const askerDir = path.resolve(opt.asker || path.join(clipsDir, 'asker'))
function askerWav(key) {
  return path.join(askerDir, `${key}.wav`)
}
/** The asks that are speech: a line the spec names with its WAV present. "tap", "home" and the
 * like are the recorder's own actions, logged in the same list, and make no sound here. */
function speechAsks(slot) {
  return asksOf(slot).filter((a) => spec.lines?.[a.line] != null && existsSync(askerWav(a.line)))
}

// Every voice at one loudness (EBU R128, -18 LUFS): the asker WAVs and the room as recorded.
const LEVEL = 'loudnorm=I=-18:TP=-2:LRA=11'
const levelledCache = new Map()
function levelled(file, extra = '') {
  const key = file + extra
  if (!levelledCache.has(key)) {
    const target = path.join(outDir, '.tmp', `lev-${levelledCache.size}.wav`)
    run('ffmpeg', ['-v', 'error', '-y', '-i', file, '-af', `${extra}${LEVEL}`, '-ar', '48000', target])
    levelledCache.set(key, target)
  }
  return levelledCache.get(key)
}

/**
 * Where a slot's Alfred audio comes from, and its offset (seconds to ADD to a video time to get
 * the source time). A phone app on the assistant audio stream cannot be captured digitally, so a
 * take is usually a silent mp4 plus `room_wav`, the room through the laptop mic, starting
 * `room_starts_before_video_s` before the video. That is cleaned lightly: a high-pass for rumble,
 * gentle FFT noise reduction, and the common loudness; nothing that changes the voice.
 */
function audioSource(slot) {
  const meta = slotMeta(slot)
  if (meta.room_wav && existsSync(path.join(clipsDir, meta.room_wav))) {
    const file = levelled(path.join(clipsDir, meta.room_wav), 'highpass=f=90,afftdn=nr=10:nf=-40,')
    return { file, offset: Number(meta.room_starts_before_video_s || 0) }
  }
  const mp4 = path.join(clipsDir, `${slot}.mp4`)
  return hasAudio(mp4) ? { file: mp4, offset: 0 } : null
}

/** A path as an ffmpeg filter option wants it: forward slashes, the drive colon escaped. */
function ffPath(p) {
  return p.replace(/\\/g, '/').replace(/^([A-Za-z]):/, '$1\\:')
}

function slotMeta(slot) {
  const file = path.join(clipsDir, `${slot}.json`)
  // PowerShell writes JSON with a byte-order mark, which JSON.parse refuses (trimStart drops it: U+FEFF is white space).
  return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8').trimStart()) : {}
}

/**
 * The made-up money figures (Kevin, 2026-11-19: "keep the whole phone in view, either make up a
 * money number or hide it first"): `spec.money_overlay` paints opaque patches in the screen's own
 * ground colour over the real figures and sets invented ones on top in the app's own face, size
 * and ink, on the footage at its native size, before it is cut or scaled.
 *
 * When: while the slot's `home_spans` say home is on screen (default: the whole take), never
 * inside its `no_overlay_spans` (a card replaces home there), and one patch (with its text) is
 * held off inside `patch_off_spans` {"<patch index>": [[a, b]]} (an opaque sheet covers that row).
 * A sheet's scrim dims home: the dimming is MEASURED per frame (`dim_probe`, a patch of plain
 * ground beside the figures) and each level gets patches and text in the dimmed colours, so a
 * patch never shows as a bright box on a dimmed screen. `outline` adds the debug outlines: lime
 * where a patch is drawn, red where it is held off.
 */
function overlayChain(slot, { outline = false } = {}) {
  const cfg = spec.money_overlay
  const meta = slotMeta(slot)
  if (!cfg || meta.overlay === false) return ''
  const spans = (list) => list.map(([a, b]) => `between(t,${a},${b})`).join('+')
  const any = (list) => (list?.length ? `gt(${spans(list)},0)` : '0')
  const home = meta.home_spans?.length ? any(meta.home_spans) : '1'
  const shown = `${home}*not(${any(meta.no_overlay_spans)})`
  const font = ffPath(path.resolve(REPO, cfg.font))
  const filters = []
  const patchOn = (k) => `${shown}*not(${any(meta.patch_off_spans?.[k])})`
  const textfiles = cfg.texts.map((text, k) => {
    const file = path.join(outDir, '.tmp', `overlay-${k}.txt`)
    writeFileSync(file, text.text)
    return file
  })
  for (const { factor, when } of dimLevels(slot)) {
    for (const [k, [x, y, w, h]] of cfg.patches.entries()) {
      filters.push(`drawbox=x=${x}:y=${y}:w=${w}:h=${h}:color=${scale(cfg.background, factor)}:t=fill:enable='${patchOn(k)}*(${when})'`)
    }
    for (const [k, text] of cfg.texts.entries()) {
      const x = text.right != null ? `${text.right}-text_w` : String(text.x)
      const on = text.patch != null ? patchOn(text.patch) : shown
      filters.push(
        `drawtext=fontfile='${font}':textfile='${ffPath(textfiles[k])}':fontsize=${text.size}:fontcolor=${scale(text.color, factor)}:` +
          `x=${x}:y=${text.baseline}-ascent:enable='${on}*(${when})'`,
      )
    }
  }
  if (outline) {
    for (const [k, [x, y, w, h]] of cfg.patches.entries()) {
      filters.push(`drawbox=x=${x - 3}:y=${y - 3}:w=${w + 6}:h=${h + 6}:color=lime:t=3:enable='${patchOn(k)}'`)
      filters.push(`drawbox=x=${x - 3}:y=${y - 3}:w=${w + 6}:h=${h + 6}:color=red:t=3:enable='not(${patchOn(k)})'`)
    }
  }
  return filters.join(',')
}

/** "0x14120F" with each channel times `factor`. */
function scale(hex, factor) {
  const n = parseInt(hex.replace(/^0x/i, ''), 16)
  const ch = [16, 8, 0].map((shift) => Math.round(((n >> shift) & 255) * factor))
  return '0x' + ch.map((c) => c.toString(16).padStart(2, '0')).join('').toUpperCase()
}

/**
 * The screen's brightness over time, as runs of one level each: [{factor, when}], `when` an
 * ffmpeg expression over t. Measured from `money_overlay.dim_probe` (plain ground) at 30 fps
 * against the ground colour's red channel, in steps of 0.05; one level of 1 without a probe.
 */
const dimCache = new Map()
function dimLevels(slot) {
  const probe = spec.money_overlay.dim_probe
  if (!probe) return [{ factor: 1, when: '1' }]
  if (dimCache.has(slot)) return dimCache.get(slot)
  const [x, y, w, h] = probe
  const ground = parseInt(spec.money_overlay.background.slice(2, 4), 16)
  const raw = spawnSync(bin('ffmpeg'), [
    '-v', 'error', '-i', path.join(clipsDir, `${slot}.mp4`), '-vf', `fps=${FPS},crop=${w}:${h}:${x}:${y},scale=1:1:flags=area`,
    '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-',
  ], { maxBuffer: 1 << 26 }).stdout
  const runs = []
  for (let f = 0; f * 3 < raw.length; f++) {
    const factor = Math.min(1, Math.round((raw[f * 3] / ground) * 20) / 20)
    const last = runs[runs.length - 1]
    if (last && last.factor === factor) last.end = f
    else runs.push({ factor, start: f, end: f })
  }
  const levels = new Map()
  for (const r of runs) {
    // Frame f covers [f/FPS, (f+1)/FPS); half-frame margins so each frame lands in one level.
    const span = `between(t,${((r.start - 0.5) / FPS).toFixed(4)},${((r.end + 0.5) / FPS).toFixed(4)})`
    levels.set(r.factor, [...(levels.get(r.factor) || []), span])
  }
  const out = [...levels].map(([factor, list]) => ({ factor, when: `gt(${list.join('+')},0)` }))
  dimCache.set(slot, out)
  return out
}

/** One frame a second of every slot's footage with the patch outlines, to catch a peeking digit. */
function overlayDebug() {
  if (!spec.money_overlay) return
  const dir = path.join(outDir, 'overlay-check')
  mkdirSync(dir, { recursive: true })
  for (const old of readdirSync(dir)) rmSync(path.join(dir, old))
  const [cx, cy, cw, ch] = spec.money_overlay.check_crop || [0, 0, 1080, 2340]
  for (const slot of slots) {
    const chain = overlayChain(slot, { outline: true })
    if (!chain) continue
    run('ffmpeg', [
      '-v', 'error', '-y', '-i', path.join(clipsDir, `${slot}.mp4`), '-vf',
      `${chain},fps=1,crop=${cw}:${ch}:${cx}:${cy},drawtext=fontfile='C\\:/Windows/Fonts/arial.ttf':text='${slot} %{pts\\:hms}':x=8:y=8:fontsize=28:fontcolor=white:box=1:boxcolor=black@0.7`,
      path.join(dir, `${slot}-%03d.png`),
    ])
  }
}

function parseVtt(text) {
  const cues = []
  const stamp = (s) => s.split(':').reduce((acc, part) => acc * 60 + Number(part), 0)
  for (const block of text.replace(/\r/g, '').split('\n\n')) {
    const lines = block.split('\n')
    const at = lines.findIndex((l) => l.includes('-->'))
    if (at < 0) continue
    const [a, b] = lines[at].split('-->').map((s) => stamp(s.trim().split(' ')[0]))
    cues.push({ start: a, end: b, text: lines.slice(at + 1).join(' ').trim() })
  }
  return cues
}

/** Captions on the video's own clock: asker lines, then the slot's own reply cues. */
function captionCues(timeline) {
  const cues = []
  for (const [k, item] of timeline.scenes.entries()) {
    const scene = spec.scenes[k] // the page's timeline is the spec's scenes, in order
    if (!scene.slot || !slots.has(scene.slot)) continue
    const speed = scene.speed || 1
    const out = footageOut(scene, item)
    const map = (c) => item.start + (c - scene.in) / speed
    const inside = (c) => c >= scene.in && c < out
    for (const ask of speechAsks(scene.slot)) {
      if (!inside(ask.at)) continue
      const len = duration(askerWav(ask.line))
      cues.push({ start: map(ask.at), end: Math.min(map(ask.at + len), item.end), text: `Asker: ${spec.lines[ask.line]}` })
    }
    const vtt = path.join(clipsDir, `${scene.slot}.vtt`)
    if (existsSync(vtt)) {
      for (const cue of parseVtt(readFileSync(vtt, 'utf8'))) {
        if (cue.end <= scene.in || cue.start >= out) continue
        if (/^(you|asker)\s*:/i.test(cue.text)) continue // the asker lines come from the log above
        cues.push({ start: map(Math.max(cue.start, scene.in)), end: map(Math.min(cue.end, out)), text: cue.text })
      }
    }
  }
  return cues.sort((a, b) => a.start - b.start)
}

/** Where a scene's footage ends: its in point plus the scene's length on screen, at its speed. */
function footageOut(scene, item) {
  return scene.in + (item.end - item.start) * (scene.speed || 1)
}

function vttStamp(t) {
  const ms = Math.round(t * 1000)
  const h = String(Math.floor(ms / 3600000)).padStart(2, '0')
  const m = String(Math.floor(ms / 60000) % 60).padStart(2, '0')
  const s = String(Math.floor(ms / 1000) % 60).padStart(2, '0')
  return `${h}:${m}:${s}.${String(ms % 1000).padStart(3, '0')}`
}

/** Draws every frame of one layout into `bgFile`; returns the page's timeline and screen rectangle. */
async function renderFrames(layout, bgFile) {
  const require = createRequire(path.join(FRONTEND, 'package.json'))
  const { chromium } = require('playwright-core')
  const [w, h] = SIZES[layout]
  const browser = await chromium.launch()
  try {
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 })
    await page.goto(pathToFileURL(path.join(HERE, 'scenes.html')).href)
    const fontUrl = pathToFileURL(
      path.join(FRONTEND, 'node_modules', '@fontsource-variable', 'source-serif-4', 'files', 'source-serif-4-latin-wght-normal.woff2'),
    ).href
    const setup = (cues) =>
      page.evaluate(([s, l, o]) => window.setup(s, l, o), [spec, layout, { fontUrl, slots: [...slots], burnCaptions: layout === 'tall', cues }])
    // The timeline is the page's own; captions are placed on it, then handed back for burning in.
    const timeline = await setup([])
    const cues = captionCues(timeline)
    await setup(cues)
    const rect = await page.evaluate(() => window.screenRect())
    const from = Number(opt.from || 0), to = Math.min(Number(opt.to || timeline.total), timeline.total)
    const ff = spawn(bin('ffmpeg'), [
      '-v', 'error', '-y', '-f', 'image2pipe', '-c:v', 'mjpeg', '-framerate', String(FPS), '-i', '-',
      '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '12', '-pix_fmt', 'yuv420p', bgFile,
    ], { stdio: ['pipe', 'inherit', 'inherit'] })
    const done = new Promise((resolve, reject) => ff.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exit ${code}`)))))
    const frames = Math.round((to - from) * FPS)
    for (let f = 0; f < frames; f++) {
      await page.evaluate((t) => window.frame(t), from + f / FPS)
      const jpg = await page.screenshot({ type: 'jpeg', quality: 92 })
      if (!ff.stdin.write(jpg)) await new Promise((r) => ff.stdin.once('drain', r))
      if (f % (FPS * 10) === 0) process.stdout.write(`  ${layout} ${(f / FPS).toFixed(0)}s\n`)
    }
    ff.stdin.end()
    await done
    return { timeline, rect, from, to, cues }
  } finally {
    await browser.close()
  }
}

/** Footage into the screen rectangle, and the sound, over the drawn frames. */
function composite(layout, bgFile, { timeline, rect, from, to }, outFile) {
  const inputs = ['-i', bgFile]
  const vf = []
  const af = []
  let last = '[0:v]'
  let n = 1
  const audioParts = []
  const total = to - from
  timeline.scenes.forEach((item, k) => {
    const scene = spec.scenes[k]
    if (!scene.slot || !slots.has(scene.slot)) return
    const start = item.start - from, end = item.end - from
    if (end <= 0 || start >= total) return
    const file = path.join(clipsDir, `${scene.slot}.mp4`)
    const speed = scene.speed || 1
    const out = footageOut(scene, item)
    inputs.push('-i', file)
    const v = n++
    const fadeOut = Math.max(end - start - 0.3, 0)
    const money = overlayChain(scene.slot)
    vf.push(
      `[${v}:v]${money ? money + ',' : ''}trim=${scene.in}:${out.toFixed(3)},setpts=(PTS-STARTPTS)/${speed},fps=${FPS},` +
        `scale=${rect.w}:${rect.h}:force_original_aspect_ratio=decrease,pad=${rect.w}:${rect.h}:(ow-iw)/2:(oh-ih)/2:color=0x0b0a09,` +
        `format=yuva420p,fade=t=in:st=0:d=0.3:alpha=1,fade=t=out:st=${fadeOut.toFixed(3)}:d=0.3:alpha=1,` +
        `setpts=PTS+${start.toFixed(3)}/TB[c${v}]`,
      `${last}[c${v}]overlay=${rect.x}:${rect.y}:eof_action=pass:enable='between(t,${start.toFixed(3)},${end.toFixed(3)})'[o${v}]`,
    )
    last = `[o${v}]`
    // Sound: Alfred from the take's own audio (the room recording, or the mp4's track) at 1x
    // only, and each asker line's clean WAV laid at its time. Nothing is muted unless an ask says
    // `mute` (a track that also carries the asker): muting by default would silence a barge-in.
    const source = speed === 1 ? audioSource(scene.slot) : null
    if (!source) return
    const asks = speechAsks(scene.slot).filter((a) => a.at >= scene.in && a.at < out)
    const mute = asks.filter((a) => a.mute).map((a) => {
      const len = duration(askerWav(a.line))
      return `between(t,${(a.at - scene.in - 0.25).toFixed(3)},${(a.at - scene.in + len + 0.25).toFixed(3)})`
    })
    const volume = mute.length ? `,volume='if(${mute.join('+')},0,1)':eval=frame` : ''
    const delay = Math.round(start * 1000)
    inputs.push('-i', source.file)
    const r = n++
    const from0 = scene.in + source.offset
    const to0 = out + source.offset
    // A room file that starts after the video (negative offset) is padded with silence first.
    const lead = from0 < 0 ? `adelay=${Math.round(-from0 * 1000)}|${Math.round(-from0 * 1000)},` : ''
    af.push(
      `[${r}:a]${lead}atrim=${Math.max(from0, 0).toFixed(3)}:${(to0 - Math.min(from0, 0)).toFixed(3)},asetpts=PTS-STARTPTS,` +
        `aresample=48000,aformat=channel_layouts=stereo${volume},afade=t=in:d=0.15,` +
        `afade=t=out:st=${Math.max(end - start - 0.3, 0).toFixed(3)}:d=0.3,adelay=${delay}|${delay}[a${r}]`,
    )
    audioParts.push(`[a${r}]`)
    for (const a of asks) {
      inputs.push('-i', levelled(askerWav(a.line)))
      const w = n++
      const d = Math.round((start + (a.at - scene.in)) * 1000)
      af.push(`[${w}:a]aresample=48000,aformat=channel_layouts=stereo,adelay=${d}|${d}[s${w}]`)
      audioParts.push(`[s${w}]`)
    }
  })
  af.push(`anullsrc=r=48000:cl=stereo,atrim=0:${total.toFixed(3)}[silence]`)
  af.push(`[silence]${audioParts.join('')}amix=inputs=${audioParts.length + 1}:normalize=0:duration=first[aout]`)
  vf.push(`${last}format=yuv420p[vout]`)
  const crf = layout === 'tall' ? '22' : '21'
  run('ffmpeg', [
    '-v', 'error', '-y', ...inputs, '-filter_complex', [...vf, ...af].join(';'),
    '-map', '[vout]', '-map', '[aout]', '-r', String(FPS),
    '-c:v', 'libx264', '-preset', 'medium', '-crf', crf, '-pix_fmt', 'yuv420p', '-profile:v', 'high',
    '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-movflags', '+faststart', '-t', total.toFixed(3), outFile,
  ])
}

function chaptersOf(timeline) {
  return timeline.scenes
    .map((item, k) => ({ item, scene: spec.scenes[k] }))
    .filter(({ scene }) => (scene.kind === 'beat' || scene.kind === 'step') && scene.chapter !== false)
    .map(({ item, scene }) => ({ title: scene.title, line: scene.line || '', at: Math.round(item.start * 10) / 10 }))
}

async function main() {
  const layouts = opt.layout === 'both' ? ['wide', 'tall'] : [opt.layout]
  const tmp = path.join(outDir, '.tmp')
  mkdirSync(tmp, { recursive: true })
  overlayDebug()
  if (opt.check === 'overlay') return
  for (const layout of layouts) {
    const bgFile = path.join(tmp, `bg-${layout}.mp4`)
    const info = await renderFrames(layout, bgFile)
    const outFile = path.join(outDir, `${opt.name}-${layout === 'wide' ? '16x9' : '4x5'}.mp4`)
    composite(layout, bgFile, info, outFile)
    console.log(`${outFile}: ${duration(outFile).toFixed(2)} s`)
    if (layout === 'wide') {
      const lines = ['WEBVTT', '']
      for (const c of info.cues) lines.push(`${vttStamp(c.start - info.from)} --> ${vttStamp(c.end - info.from)}`, c.text, '')
      writeFileSync(path.join(outDir, `${opt.name}.vtt`), lines.join('\n'))
      writeFileSync(path.join(outDir, 'chapters.json'), JSON.stringify(chaptersOf(info.timeline), null, 2) + '\n')
      const posterAt = spec.poster_at ?? 0.5
      run('ffmpeg', ['-v', 'error', '-y', '-ss', String(posterAt), '-i', outFile, '-frames:v', '1', path.join(outDir, 'poster.png')])
      // Contact sheets: one frame a second, timestamped, 6 x 4 to a sheet.
      for (const old of readdirSync(outDir).filter((f) => /^sheet-\d+\.png$/.test(f))) rmSync(path.join(outDir, old))
      run('ffmpeg', [
        '-v', 'error', '-y', '-i', outFile, '-vf',
        "fps=1,scale=480:-2,drawtext=text='%{pts\\:hms}':x=8:y=8:fontsize=22:fontcolor=white:box=1:boxcolor=black@0.7:fontfile='C\\:/Windows/Fonts/arial.ttf',tile=6x4",
        path.join(outDir, 'sheet-%02d.png'),
      ])
    }
  }
  rmSync(tmp, { recursive: true, force: true })
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
