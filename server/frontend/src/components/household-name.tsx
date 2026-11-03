import { useHousehold } from '@/api/queries'

/**
 * The household's name, for the shell's header. The app is LEGION and the
 * household is the thing with a name; the shell never carries an assistant's
 * (CLAUDE.md section 1).
 *
 * Three states and three different renderings, as everywhere else: still asking
 * (just "LEGION"), the name, and could not reach the engine (said in words under
 * "LEGION", never an empty header that reads as "no household").
 */
export function HouseholdName({ className = '', rail = false }: { className?: string; rail?: boolean }) {
  const household = useHousehold(true)
  const name = household.data ? household.data.name : 'LEGION'

  if (rail) {
    // The slim desk rail: the initial on a tile, the name in small words under it.
    return (
      <div className={`flex w-full flex-col items-center gap-1 text-center ${className}`}>
        <span
          aria-hidden="true"
          className="grid size-11 place-items-center rounded-[0.875rem] bg-primary text-[0.9375rem] font-bold text-primary-foreground"
        >
          {name.trim().charAt(0).toUpperCase() || 'L'}
        </span>
        <p className="line-clamp-2 w-full text-[0.6875rem] leading-tight font-medium text-muted-foreground">{name}</p>
        {household.isError && <p className="text-[0.6875rem] leading-tight text-muted-foreground">Could not reach the engine.</p>}
      </div>
    )
  }

  return (
    <div className={`min-w-0 ${className}`}>
      <p className="truncate text-lg leading-tight font-medium">
        {household.data ? household.data.name : 'LEGION'}
      </p>
      {household.isError && (
        <p className="text-[0.8125rem] text-muted-foreground">Could not reach the engine.</p>
      )}
    </div>
  )
}
