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
export function ThemeToggle({ compact = false }: { compact?: boolean }) {
  const pref = useSyncExternalStore(subscribeThemePref, getThemePref, () => 'system' as const)
  const Icon = ICON[pref]
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
