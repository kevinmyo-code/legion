# Notifications, devices and account: research notes

Status: written alongside the build of web-revamp tickets 05 and 15 (2026-10-03). The join and
household screens have their own notes (`join-invite.md`, `household-members.md`).

## What I looked at

All three are public help or engineering pages, loaded in a browser and read as rendered text on
2026-10-03. Screenshots beside this file in `refs/`.

1. **WebKit, "Web Push for Web Apps on iOS and iPadOS"**
   `https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/` ->
   `refs/webkit-web-push-ios.png`. The platform's own statement of when push works on an iPhone.
2. **Google Calendar Help, "Change Google Calendar notifications"**
   `https://support.google.com/calendar/answer/37242?hl=en` -> `refs/google-calendar-notifications.png`.
   How the product LEGION's calendar most resembles lets a person choose what reaches them.
3. **Google Account Help, "See devices with account access"**
   `https://support.google.com/accounts/answer/3067630?hl=en` -> `refs/google-account-devices.png`.
   The shipped pattern for "what is signed in as me, and cut one off".

## What I could not reach

- **No live product screens.** All three are documentation, not the settings screens themselves
  (those are behind a sign-in I did not make). I took the decisions the documents state, not any
  layout. The layouts in `server/frontend/src/screens/settings/` are mine, on the ADR 0053 tokens.
- I did not look at Slack, GitHub, Cozi or Apple's own Settings app for notification toggles: time
  went to the three above. A named gap, not a claim.

## Decisions observed, and what each became

**WebKit (iOS push).**
- Push on iPhone needs iOS 16.4+ **and** the site added to the Home Screen as a web app (Share, then
  Add to Home Screen). In a Safari tab there is no push at all.
  -> `/settings/notifications` checks "iOS and not standalone" *before* asking the browser what it
  supports, and shows the three-step install card (Share, Add to Home Screen, open from the icon)
  instead of a button that cannot work. The card names iOS 16.4.
- The permission request must answer a direct tap ("such as tapping on a 'subscribe' button").
  -> The permission question is asked only from the "Turn on notifications on this device" button,
  never on page load.
- Once allowed, "the user can manage those permissions per web app in Notifications Settings, just
  like any other app". -> A denied permission is its own sentence that sends the person to their
  browser or device settings; the page offers no button that cannot work (a denied permission cannot
  be re-asked from the page).
- Notifications there integrate with Focus. -> Nothing to build; it is why every notification must
  be worth interrupting a Focus for (the compulsion test, CLAUDE.md section 7).

**Google Calendar (notifications).**
- Notification settings are **personal**: "No one else can change your notification settings,
  including people you share your calendar with." -> Preferences are per user, server-side, the same
  on every device that user turns on. The page says so ("The same on every device you turn
  notifications on for").
- A calendar-level default, overridable **per event**. -> The three kinds are the defaults here; the
  per-event part is the reminder lead time on the event sheet (ticket 14), not a settings screen.
- Browser permission is a separate prompt: "If you're asked to let the site show notifications, you
  need to allow it." -> "This device" (permission and subscription, read from the browser) is kept
  apart from "What to send" (the person's choices, read from the engine). The engine keeps no list of
  browsers to read back, so the page never claims to know about a device it cannot see.
- Google offers email, desktop and in-app alert as channels. -> One channel only. Email delivery is
  its own ticket (web-and-households 07) and is not chosen.

**Google Account (devices).**
- A list of devices **and sessions** where you are signed in, each with a sign-out, "if you're not
  sure it's yours". -> `/settings/devices`: each row has a Revoke, with one confirm sentence saying
  it stops at once and must sign in again.
- **The time shown is the last contact, not the last use**: it "can be more recent than when you
  last used the device" because background syncing counts. -> The row says "Last seen", not "Last
  used": the engine's `last_seen_at` is stamped by any authenticated request, including the phone's
  background mirror, and "used" would claim more than it knows. (This changed a string I had already
  written.)
- A session is a different thing from a device. -> The screen says in words that this browser holds
  a session, not a device token, and is not listed: someone looking for "this laptop" and finding
  nothing would otherwise wonder whether it is signed in at all.

## What would be wrong here

- Google's **email and per-channel matrix** (answers a product with mail and mobile and desktop
  channels; LEGION has one channel and a household of a few).
- Google's **"notify only if I responded Yes or Maybe"**: LEGION has no invitations or responses.
- Any **streak, "you have not opened" or re-engagement nudge**: none of these three sources suggests
  one and the project forbids it. Every string on the page and in the service worker is held to the
  compulsion test by a vitest (`-notifications.test.tsx`, `push-sw.test.ts`); the engine's own copy
  is held to it by `server/tests/test_push.py`.

## Assumptions ledger

- iOS 16.4 + installed-app requirement, tap-gated permission, per-app permission management:
  **in-browser** (read from the WebKit post as rendered).
- Google's personal-settings and per-event-override model; last-contact timestamps: **in-browser**
  (read from the two help pages as rendered).
- That the layouts are good: **in-browser** only to the extent of my own Playwright shots at 390x844
  and 1440x900 (`.scratch/web-revamp/research/shots/05/`, `/15/`). Nobody has used them on a phone.
