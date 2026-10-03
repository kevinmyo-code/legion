import { useSyncExternalStore } from 'react'

import { SettingsPage } from '@/components/settings/settings-page'
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group'
import { Panel } from '@/components/workbench/page'
import {
  THEME_LABEL,
  getThemePref,
  isThemePref,
  setThemePref,
  subscribeThemePref,
  type ThemePref,
} from '@/lib/theme'

const CHOICES: { value: ThemePref; blurb: string }[] = [
  { value: 'system', blurb: 'Follows this device. It changes by itself when the device does.' },
  { value: 'light', blurb: 'Always the light look.' },
  { value: 'dark', blurb: 'Always the dark look.' },
]

/**
 * `/settings/appearance`: System, Light or Dark. Both surfaces (spec D2, D12).
 *
 * The same stored choice the top-bar toggle cycles (`lib/theme.ts`, key
 * `legion.theme.v1`), so the two cannot disagree: both read it through
 * `useSyncExternalStore`. It is per device, and the page says so, because a
 * person who picks Dark here and finds their phone still light would otherwise
 * think the setting did not save.
 */
export function AppearanceScreen() {
  const pref = useSyncExternalStore(subscribeThemePref, getThemePref, () => 'system' as const)

  return (
    <SettingsPage title="Appearance">
      <Panel title="Theme" description="Saved on this device only. Each device keeps its own.">
        <RadioGroup
          aria-label="Theme"
          value={pref}
          onValueChange={(value) => {
            if (isThemePref(value)) setThemePref(value)
          }}
        >
          {CHOICES.map((choice) => {
            const id = `theme-${choice.value}`
            return (
              <label
                key={choice.value}
                htmlFor={id}
                className="flex min-h-14 cursor-pointer items-center gap-4 rounded-control bg-surface-2 px-4 py-3 has-[[data-state=checked]]:bg-primary-container"
              >
                <RadioGroupItem id={id} value={choice.value} />
                <span className="flex flex-col">
                  <span className="text-[0.9375rem] font-medium">{THEME_LABEL[choice.value]}</span>
                  <span className="text-[0.8125rem] text-muted-foreground">{choice.blurb}</span>
                </span>
              </label>
            )
          })}
        </RadioGroup>
      </Panel>
    </SettingsPage>
  )
}
