import { useEffect, useRef, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react'

import { cn } from '@/lib/utils'

import { REPEATING, createRepeater, type PadAction, type Repeater } from './input'

/**
 * The on-screen gamepad: a D-pad left, A and B right, Hold across the top and
 * small Start and Select between. Pointer events with capture, so a thumb that
 * slides off a button still releases it; Left, Right and Down auto-repeat.
 */

function buzz() {
  try {
    navigator.vibrate?.(10)
  } catch {
    // No haptics here.
  }
}

interface PadButtonProps {
  action: PadAction
  label: string
  press: (action: PadAction) => void
  disabled?: boolean
  className?: string
  children: ReactNode
}

export function PadButton({ action, label, press, disabled, className, children }: PadButtonProps) {
  const pressRef = useRef(press)
  useEffect(() => {
    pressRef.current = press
  })
  const repeater = useRef<Repeater | null>(null)
  if (repeater.current === null) {
    repeater.current = createRepeater(() => pressRef.current(action), REPEATING.has(action))
  }
  // A button that unmounts mid-press must not keep firing.
  useEffect(() => {
    const current = repeater.current
    return () => current?.stop()
  }, [])
  useEffect(() => {
    if (disabled) repeater.current?.stop()
  }, [disabled])

  const down = (event: ReactPointerEvent<HTMLButtonElement>) => {
    event.preventDefault()
    if (disabled) return
    event.currentTarget.setPointerCapture?.(event.pointerId)
    buzz()
    repeater.current?.start()
  }
  const up = () => repeater.current?.stop()

  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      tabIndex={-1}
      data-pad={action}
      onPointerDown={down}
      onPointerUp={up}
      onPointerCancel={up}
      onPointerLeave={up}
      onLostPointerCapture={up}
      onContextMenu={(event) => event.preventDefault()}
      onMouseDown={(event) => event.preventDefault()}
      // A screen reader or switch user activates with a click that has no pointer press.
      onClick={(event) => {
        if (event.detail === 0 && !disabled) pressRef.current(action)
      }}
      className={cn(
        'flex touch-none select-none items-center justify-center bg-surface-3 text-foreground',
        'active:bg-primary active:text-primary-foreground disabled:opacity-40',
        '[-webkit-touch-callout:none] [-webkit-tap-highlight-color:transparent]',
        className,
      )}
    >
      {children}
    </button>
  )
}

export interface GamePadProps {
  press: (action: PadAction) => void
  holdDisabled: boolean
  /** Start pauses; a race cannot, so it is off there. */
  startDisabled: boolean
  className?: string
}

const ARROW = 'h-12 w-12 text-[1.25rem]'

export function GamePad({ press, holdDisabled, startDisabled, className }: GamePadProps) {
  const pill = 'h-12 w-20 rounded-full text-[0.8125rem]'
  return (
    <div
      role="group"
      aria-label="Game controls"
      className={cn('flex w-full max-w-[22rem] items-center justify-between gap-1 self-center', className)}
    >
      {/* D-pad. */}
      <div className="grid shrink-0 grid-cols-3 grid-rows-3" style={{ width: '9rem' }}>
        <PadButton action="up" label="Hard drop" press={press} className={cn(ARROW, 'col-start-2 row-start-1 rounded-t-control')}>
          {'▲'}
        </PadButton>
        <PadButton action="left" label="Move left" press={press} className={cn(ARROW, 'col-start-1 row-start-2 rounded-l-control')}>
          {'◀'}
        </PadButton>
        <div className="col-start-2 row-start-2 bg-surface-3" aria-hidden="true" />
        <PadButton action="right" label="Move right" press={press} className={cn(ARROW, 'col-start-3 row-start-2 rounded-r-control')}>
          {'▶'}
        </PadButton>
        <PadButton action="down" label="Soft drop" press={press} className={cn(ARROW, 'col-start-2 row-start-3 rounded-b-control')}>
          {'▼'}
        </PadButton>
      </div>

      {/* Hold shoulder, then Select and Start, down the middle. */}
      <div className="flex shrink-0 flex-col items-center gap-1">
        <PadButton action="hold" label="Hold" press={press} disabled={holdDisabled} className={cn(pill, 'font-medium')}>
          Hold
        </PadButton>
        <PadButton action="select" label="Change theme" press={press} className={pill}>
          Theme
        </PadButton>
        <PadButton action="start" label="Pause" press={press} disabled={startDisabled} className={pill}>
          Pause
        </PadButton>
      </div>

      {/* A and B. */}
      <div className="relative shrink-0" style={{ width: '6.75rem', height: '8.5rem' }}>
        <PadButton
          action="rotateCcw"
          label="Rotate counter-clockwise"
          press={press}
          className="absolute bottom-3 left-0 h-14 w-14 rounded-full text-[1.125rem] font-semibold"
        >
          B
        </PadButton>
        <PadButton
          action="rotateCw"
          label="Rotate clockwise"
          press={press}
          className="absolute right-0 top-3 h-14 w-14 rounded-full text-[1.125rem] font-semibold"
        >
          A
        </PadButton>
      </div>
    </div>
  )
}
