import { Box } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import type { Stage } from '@/components/demo/model-stage'

import { usePresenter } from './presenter-context'
import type { Part3dCard, PartFact } from './types'

/**
 * A Jeep front-end part in 3D (`show_part`, `server/garage/tool.py`). The modal holds the model,
 * flown to the part and lit up; the chat keeps a short card that opens it again. Under the model,
 * in words, every fastening point of the part: a torque figure only for a row the owner checked,
 * with its source and page; otherwise "Not verified yet", never a number (the engine sends none).
 */

export const NOT_VERIFIED = 'Not verified yet'
export const MODEL_FAILED = 'The 3D view could not load here. Where it is, in words, is above.'
const FLY_MS = 600

function reducedMotion(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function Facts({ card }: { card: Part3dCard }) {
  if (!card.facts.length) return null
  return (
    <ul data-card="part-facts" className="m-0 flex list-none flex-col gap-1.5 p-0 text-[0.875rem]">
      {card.facts.map((fact) => (
        <li
          key={fact.fastener_id}
          data-fastener={fact.fastener_id}
          aria-current={fact.fastener_id === card.fastener_id ? 'true' : undefined}
          className="rounded-md border border-outline-variant px-2.5 py-1.5 aria-[current=true]:border-primary"
        >
          <FactLine fact={fact} />
        </li>
      ))}
    </ul>
  )
}

function FactLine({ fact }: { fact: PartFact }) {
  return (
    <>
      <span className="font-semibold">{fact.name}</span>
      {fact.checked && fact.figure ? (
        <>
          <span className="block tabular-nums">Torque: {fact.figure}</span>
          {(fact.socket || fact.thread) && (
            <span className="block">{[fact.socket && `Socket ${fact.socket}`, fact.thread && `Thread ${fact.thread}`].filter(Boolean).join(' · ')}</span>
          )}
          <span className="block text-[0.8125rem] text-muted-foreground">
            For {fact.applies_to}. {fact.source}, {fact.page}. Checked {fact.verified_on}.
          </span>
        </>
      ) : (
        <span className="block text-muted-foreground">Torque: {NOT_VERIFIED}</span>
      )}
    </>
  )
}

/** The card in the chat: what was asked about, its fastening points, and a button to the 3D view. */
export function PartView({ card }: { card: Part3dCard }) {
  const presenter = usePresenter()
  return (
    <div data-card="part3d" className="flex flex-col gap-2">
      <p className="m-0">{card.where}</p>
      <button
        type="button"
        data-card="part-open"
        onClick={() => presenter?.show(card)}
        disabled={!presenter}
        className="flex items-center gap-1.5 self-start rounded-md border border-outline-variant px-2.5 py-1.5 focus-visible:outline-2 disabled:opacity-60"
      >
        <Box className="size-4" aria-hidden="true" />
        Open the 3D view
      </button>
      <Facts card={card} />
    </div>
  )
}

type State = 'loading' | 'ready' | 'failed'

/** The model in the modal, flown to the part, with the fastening points in words under it. */
export function PartBody({ card }: { card: Part3dCard }) {
  const host = useRef<HTMLDivElement>(null)
  const stage = useRef<Stage | null>(null)
  const [state, setState] = useState<State>('loading')

  useEffect(() => {
    const element = host.current
    if (!element) return
    let cancelled = false
    setState('loading')
    import('@/components/demo/model-stage')
      .then(({ mountStage }) =>
        mountStage(element, card.model, {
          autoTurn: false,
          explore: true,
          onLost: () => {
            if (cancelled) return
            stage.current?.dispose()
            stage.current = null
            setState('failed')
          },
        }),
      )
      .then((mounted) => {
        if (cancelled) {
          mounted.dispose()
          return
        }
        stage.current = mounted
        mounted.setActive(true)
        setState('ready')
      })
      .catch(() => {
        if (!cancelled) setState('failed')
      })
    return () => {
      cancelled = true
      stage.current?.dispose()
      stage.current = null
    }
  }, [card.model])

  // A newer card for the same model flies the camera on; it does not load the model again.
  const highlightKey = card.highlight.join('\u0000')
  const viewKey = [...card.view.target, ...card.view.from].join(',')
  useEffect(() => {
    if (state !== 'ready' || !stage.current) return
    stage.current.highlight(highlightKey ? highlightKey.split('\u0000') : [])
    const [tx, ty, tz, fx, fy, fz] = viewKey.split(',').map(Number)
    stage.current.focus({ target: [tx, ty, tz], from: [fx, fy, fz] }, reducedMotion() ? 0 : FLY_MS)
  }, [state, highlightKey, viewKey])

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2 overflow-auto">
      <div
        ref={host}
        data-modal="part-stage"
        data-state={state}
        role="img"
        aria-label={`3D model of the front end, showing: ${card.title}. Drag to turn, pinch to zoom, two fingers to slide.`}
        className="relative min-h-[45vh] flex-1 overflow-hidden rounded-md border border-outline-variant [&>canvas]:block [&>canvas]:h-full [&>canvas]:w-full"
      />
      {state === 'loading' && <p className="m-0 text-[0.8125rem] text-muted-foreground">Loading the 3D view.</p>}
      {state === 'failed' && <p className="m-0 text-[0.8125rem]">{MODEL_FAILED}</p>}
      <Facts card={card} />
      {card.note && <p className="m-0 text-[0.8125rem] text-muted-foreground">{card.note}</p>}
    </div>
  )
}
