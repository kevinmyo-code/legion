import { Bell, Palette, Smartphone, UserRound, Users, type LucideIcon } from 'lucide-react'

/**
 * The sections of Settings, in one table, so the family index list and the
 * workbench's side list cannot disagree about what exists (the reasoning
 * `lib/nav.ts` applies to the tab bar and the rail).
 *
 * `blurb` is the line under the title on the index. It says what the section is
 * FOR, plainly, and never what a person has or has not done in the app.
 */
export interface SettingsSection {
  to:
    | '/settings/account'
    | '/settings/household'
    | '/settings/notifications'
    | '/settings/devices'
    | '/settings/appearance'
  label: string
  blurb: string
  icon: LucideIcon
}

export const SETTINGS_SECTIONS: readonly SettingsSection[] = [
  {
    to: '/settings/account',
    label: 'Account',
    blurb: 'Your name, your password, and signing out.',
    icon: UserRound,
  },
  {
    to: '/settings/household',
    label: 'Household',
    blurb: 'Its name, who is in it, and invite links.',
    icon: Users,
  },
  {
    to: '/settings/notifications',
    label: 'Notifications',
    blurb: 'What is sent to your devices, and whether this one gets it.',
    icon: Bell,
  },
  {
    to: '/settings/devices',
    label: 'Devices',
    blurb: 'Phones signed in to your account, and cutting one off.',
    icon: Smartphone,
  },
  {
    to: '/settings/appearance',
    label: 'Appearance',
    blurb: 'Light, dark, or whatever this device is set to.',
    icon: Palette,
  },
]

