import { Link, useNavigate } from '@tanstack/react-router'
import { useState } from 'react'

import { Button } from '@/components/ui/button'
import { Numbers, Sidebar, usePlayfield } from '@/games/tetris/playfield'

/** A fresh uint32 for a solo game. A race's seed comes from the engine instead. */
function randomSeed(): number {
  const one = new Uint32Array(1)
  crypto.getRandomValues(one)
  return one[0]
}

/**
 * `/games/solo`: Tetris with no clock and no engine. Works with the engine down,
 * because nothing here talks to it.
 */
export function GamesSoloScreen() {
  const [seed, setSeed] = useState(randomSeed)
  const navigate = useNavigate()
  const field = usePlayfield(seed, true, undefined, {
    canPause: true,
    onQuit: () => void navigate({ to: '/games' }),
  })

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-3 pb-16">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-[1.5rem] font-medium">Tetris</h1>
        <Button asChild variant="ghost">
          <Link to="/games">Games</Link>
        </Button>
      </div>
      <Numbers stats={field.stats} />
      <div className="flex items-start gap-3">
        {field.board}
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <Sidebar stats={field.stats} theme={field.theme} />
          {field.themePicker}
          {field.pauseButton}
          {field.holdButton}
        </div>
      </div>
      {field.controls}
      {field.stats.over && (
        <div className="flex flex-col gap-3 rounded-sheet bg-card p-5" role="status">
          <p className="text-[1rem]">
            Game over. You scored {field.stats.score.toLocaleString()} with {field.stats.lines} lines.
          </p>
          <Button onClick={() => setSeed(randomSeed())}>Play again</Button>
        </div>
      )}
    </div>
  )
}
