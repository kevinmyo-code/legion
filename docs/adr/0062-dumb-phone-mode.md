---
status: accepted
decided: 2026-10-22
decided-by: Kevin
supersedes: []
source: "[[decisions#2026-10-22 - Dumb phone mode (Kevin)]]"
tags: [adr]
---

# 62. Dumb phone mode is a manual switch that shrinks HOME, holds notifications and silences strangers

## Standing

**Dumb mode is a switch Kevin turns on by hand.** From a HOME switch, a Settings row or a
quick-settings tile. No schedule, no voice command, no geofence. While on, LEGION's HOME is a text list
(prototype A, `.scratch/dumb-phone/research/Main.dc.html`): a big clock, the date, "dumb for 2h 14m",
then Phone, Messages, Maps, Camera, Music, To-dos, Calendar, Books, Assistant, a footer naming the
chosen people, and Leave. Maps is LEGION's own nav screen (ADR 0054); To-dos, Calendar, Books and
Assistant are LEGION's own screens. Phone, Messages, Camera and Music resolve at run time through the
default dialer, the default SMS package, the camera intent and the Spotify package; no OEM package name
is hard-coded (clone-and-run). Games are hidden.

**Leaving is friction, not a lock.** A 60 second wait that runs on the wall clock from the tap and is
persisted, so locking the phone or killing the app does not reset it, then the typed sentence "I am
choosing to leave dumb mode" (case-insensitive, trimmed). "Stay in dumb mode" is the primary button.
Settings stays reachable on purpose.

**Allowlist, not blocklist.** Everything not kept is hidden, including the browser, and a newly
installed app is hidden automatically. A hidden app opened another way (a notification tap, a shared
link, Settings > Apps) is bounced back to HOME with a "Dumb mode is on" screen.

**Chosen people get through.** A small list picked inside LEGION from contacts (Android's starred flag
is not touched). Their notifications pass on any app and are re-alerted over Do Not Disturb; their
calls ring. Every other notification is held silently and summarised when he leaves. A caller who rings
twice within 15 minutes rings the second time.

**The digest is read-through (CLAUDE.md section 7).** It stores the app, a count and the time held.
Never a title, never text; nothing from a notification reaches Room or disk. The prototype's per-app
gist line does not ship.

**The streak counts time away.** A streak day is a local day with at least 60 minutes of dumb mode in
total; consecutive days make the streak. CLAUDE.md section 7's compulsion bullet bans streaks that pull
him back into an app; a streak that measures time away from the phone is allowed (Kevin, 2026-10-22).

**The look is Matrix code rain, not grayscale (Kevin, 2026-10-22, later the same day).** Grayscale was
ruled first and dropped within hours; it is not built. Instead, while the mode is on, Home A sits on
animated green code rain (classic movie speed) with the text list on a dark scrim and a Matrix-green on
black palette. The same renderer is a live wallpaper (home and lock; Samsung may refuse the lock screen)
and a charging screensaver. Nothing here writes system settings, so no adb step is needed.

## Context

Kevin's ADHD. No social media, no doomscrolling; only Mia and his parents reach him. He designed it
through an interview and clickable prototypes and picked home layout A over the tile layouts.

## Consequences

- One-time grants, each said in words on a setup screen with the system page to open: notification
  access (already granted for now-playing), Do Not Disturb access, the call screening role (displaces
  Samsung Smart Call), and an accessibility service (sideloaded apps need "Allow restricted settings" in
  App info first). Dumb mode still turns on with a grant missing, works for what it can and says what is
  not active. Two optional rows open the live-wallpaper picker and Screen saver settings.
- `MediaNotificationListener` now also reads notification metadata while the mode is on; it still
  stores no content.
- No voice tool: the mode is manual only, so ADR 0035's rule has nothing to mirror.
- `DumbModeController` is a constructor-injected class held by the Application. Hilt is architecture
  ticket 02 and not in the build yet; the holder is the injection point until it lands.

## Amendment, 2026-10-23: the user-facing name is "Matrix mode"

Kevin: *"i wouldnt call it dumb mode, more like matrix mode"*. Every string a person sees is now "Matrix mode": the tile, settings, setup, Leave, Digest and Bounce screens, the accessibility and wallpaper descriptions, and the leave sentence ("I am choosing to leave matrix mode"). The status line reads "in the matrix for 2h 14m". The code keeps `dumb` in its package, class and preference names, so stored state and the setup grants survive the rename. "Dumb mode" in code and in this ADR means Matrix mode.
