import { AudioError, type AudioHandlers, type AudioIO } from './audio'
import { pcm16ToFloat } from './pcm'
import captureWorkletUrl from './capture.worklet.ts?worker&url'

/**
 * Capture and playback on Web Audio.
 *
 * **Capture:** `getUserMedia` with echo cancellation on (so the model does not
 * hear itself from the speaker and interrupt itself), into a context at the
 * device's own rate, through the `legion-capture` worklet, which resamples to
 * 16 kHz and posts 20 ms PCM16 chunks. The mic is requested FIRST and on the
 * tap's stack, before any `await` that is not the permission itself.
 *
 * **Playback:** a second context at 24 kHz (Gemini's output rate), each reply
 * chunk an `AudioBuffer` scheduled back to back on the context clock, with a
 * small lead on the first so a late network packet does not stutter the start.
 * An interruption stops every scheduled source and resets the clock.
 *
 * Two contexts, not one: research/01 section 4 prefers a capture context and a
 * playback context so 24 kHz speech is never squeezed through a 16 kHz one.
 *
 * Not testable in jsdom (no Web Audio); `e2e/assistant.spec.ts` drives it in
 * Chromium with a fake microphone, and the iPhone behaviour is owed on the phone.
 */

const OUTPUT_RATE = 24_000
/** Lead before the first chunk of a burst: absorbs one jittery packet. */
const LEAD_SECONDS = 0.06

type AudioSessionNavigator = Navigator & { audioSession?: { type: string } }

export function createWebAudio(): AudioIO {
  let capture: AudioContext | null = null
  let playback: AudioContext | null = null
  let stream: MediaStream | null = null
  let source: MediaStreamAudioSourceNode | null = null
  let node: AudioWorkletNode | null = null
  const sources = new Set<AudioBufferSourceNode>()
  let nextStart = 0
  let idleTimer: ReturnType<typeof setTimeout> | null = null
  let handlers: AudioHandlers | null = null
  let sounding = false

  const setSounding = (value: boolean) => {
    if (sounding === value) return
    sounding = value
    handlers?.onPlaying(value)
  }

  const armIdle = () => {
    if (idleTimer) clearTimeout(idleTimer)
    if (!playback) return
    const wait = Math.max(0, nextStart - playback.currentTime) * 1000 + 80
    idleTimer = setTimeout(() => {
      if (playback && playback.currentTime >= nextStart - 0.01) setSounding(false)
      else armIdle()
    }, wait)
  }

  const ensurePlayback = (): AudioContext => {
    if (!playback) {
      try {
        playback = new AudioContext({ sampleRate: OUTPUT_RATE })
      } catch {
        // A browser that refuses a fixed rate still plays 24 kHz buffers: Web Audio
        // resamples each `AudioBuffer` to the context's rate.
        playback = new AudioContext()
      }
    }
    if (playback.state === 'suspended') void playback.resume()
    return playback
  }

  const stopMic = () => {
    if (node) {
      node.port.onmessage = null
      node.port.close()
      node.disconnect()
    }
    source?.disconnect()
    stream?.getTracks().forEach((track) => {
      track.onended = null
      track.stop()
    })
    if (capture) {
      capture.onstatechange = null
      void capture.close()
    }
    node = null
    source = null
    stream = null
    capture = null
  }

  return {
    async startMic(next) {
      handlers = next
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new AudioError('unavailable', 'This browser has no microphone access.')
      }
      // Everything that needs the gesture happens before the first await settles.
      try {
        const session = (navigator as AudioSessionNavigator).audioSession
        if (session) session.type = 'play-and-record'
      } catch {
        // Not supported or refused: the default routing is the fallback.
      }
      const context = new AudioContext()
      capture = context
      void context.resume()
      ensurePlayback()
      const pending = navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
      })
      let opened: MediaStream
      try {
        opened = await pending
      } catch (error) {
        const name = error instanceof DOMException ? error.name : ''
        stopMic()
        if (name === 'NotAllowedError' || name === 'SecurityError') {
          throw new AudioError('denied', 'The microphone is blocked.')
        }
        throw new AudioError('unavailable', 'No microphone could be opened.')
      }
      if (capture !== context) {
        // stopMic() ran while the permission prompt was up: the tap was undone.
        opened.getTracks().forEach((track) => track.stop())
        throw new AudioError('unavailable', 'Capture was stopped before it began.')
      }
      stream = opened
      try {
        await context.audioWorklet.addModule(captureWorkletUrl)
        node = new AudioWorkletNode(context, 'legion-capture', {
          numberOfInputs: 1,
          numberOfOutputs: 0,
          channelCount: 1,
        })
        node.port.onmessage = (event: MessageEvent<Int16Array>) => handlers?.onChunk(event.data)
        source = context.createMediaStreamSource(opened)
        source.connect(node)
      } catch {
        stopMic()
        throw new AudioError('unavailable', 'The audio worklet could not start.')
      }
      opened.getAudioTracks().forEach((track) => {
        track.onended = () => handlers?.onMicLost()
      })
      context.onstatechange = () => {
        // iOS puts a context into "interrupted" for a phone call or Siri.
        if ((context.state as string) === 'interrupted') handlers?.onMicLost()
      }
    },

    stopMic,

    play(pcm, rate) {
      const context = ensurePlayback()
      const buffer = context.createBuffer(1, pcm.length, rate)
      buffer.copyToChannel(new Float32Array(pcm16ToFloat(pcm)), 0)
      const sourceNode = context.createBufferSource()
      sourceNode.buffer = buffer
      sourceNode.connect(context.destination)
      const startAt = Math.max(context.currentTime + LEAD_SECONDS, nextStart)
      sourceNode.start(startAt)
      nextStart = startAt + buffer.duration
      sources.add(sourceNode)
      sourceNode.onended = () => sources.delete(sourceNode)
      setSounding(true)
      armIdle()
    },

    flush() {
      for (const queued of sources) {
        queued.onended = null
        try {
          queued.stop()
        } catch {
          // Already finished.
        }
      }
      sources.clear()
      nextStart = 0
      if (idleTimer) clearTimeout(idleTimer)
      idleTimer = null
      setSounding(false)
    },

    dispose() {
      this.flush()
      stopMic()
      void playback?.close()
      playback = null
      handlers = null
    },
  }
}
