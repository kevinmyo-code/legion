import { Monitor, Moon, Sun } from 'lucide-react'
import { useSyncExternalStore } from 'react'

import { Button } from '@/components/ui/button'
import {
  THEME_LABEL,
  getThemePref,
  nextThemePref,
  setThemePref,
  subscribeThemePref,
} from '@/lib/theme'

const ICON = { system: Monitor, light: Sun, dark: Moon } as const

/**
 * One button that cycles System, Light, Dark.
 *
 * It says the current choice in words, not by an icon alone: a sun that may mean
 * "you are in light mode" or "tap for light mode" is a guess, and the label
 * "Theme: System" is not. `compact` drops the visible word for the family top
 * bar, where the icon is the whole button, and keeps it as the accessible name.
 */
export function ThemeToggle({ compact = false, rail = false }: { compact?: boolean; rail?: boolean }) {
  const pref = useSyncExternalStore(subscribeThemePref, getThemePref, () => 'system' as const)
  const Icon = ICON[pref]
  if (rail) {
    // The slim desk rail: icon over the word, like the rail's own entries.
    return (
      <button
        type="button"
        aria-label={`Theme: ${THEME_LABEL[pref]}. Tap to change.`}
        onClick={() => setThemePref(nextThemePref(pref))}
        className="flex min-h-[3.375rem] w-full flex-col items-center justify-center gap-1 rounded-2xl py-1.5 text-[0.71875rem] font-medium text-muted-foreground transition-colors outline-none hover:bg-surface-3 focus-visible:outline-2 focus-visible:outline-primary"
      >
        <Icon aria-hidden="true" className="size-5" />
        {THEME_LABEL[pref]}
      </button>
    )
  }
  return (
    <Button
      type="button"
      variant="secondary"
      size={compact ? 'icon-sm' : 'sm'}
      aria-label={`Theme: ${THEME_LABEL[pref]}. Tap to change.`}
      onClick={() => setThemePref(nextThemePref(pref))}
    >
      <Icon />
      {!compact && <span>{THEME_LABEL[pref]}</span>}
    </Button>
  )
}
