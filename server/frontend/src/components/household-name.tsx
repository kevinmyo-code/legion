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
export function HouseholdName({ className = '' }: { className?: string }) {
  const household = useHousehold(true)

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
