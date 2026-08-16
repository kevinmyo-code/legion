# Notification listener: the phone as a sense

Type: grilling
Status: archived (Kevin, 2026-08-16) - not needed for now
Blocked by: -

## Question

`NotificationListenerService` gives LEGION ambient awareness of the phone itself: who texted, what
app is yelling, what arrived while driving. Kevin called it gold. It is also the widest privacy
aperture on this map - every app's notifications, including 2FA codes, medical apps, other
people's messages on the lockscreen. Decide:

1. **Allowlist or denylist.** Which apps' notifications does LEGION see at all? An explicit
   allowlist Kevin curates (messaging, calendar, maybe banking) is the §4-shaped answer; a
   denylist leaks by default. Where does the list live and how is it edited?
2. **What reaches the model.** The mail read-through rule (google-account ticket 07) is the
   precedent: read, used, dropped, never stored, excluded from `EpisodicTurn`/`CompanionMemory`.
   Does it apply verbatim? Notification content includes message bodies from people who never
   consented to an LLM reading them - is content ever sent to Gemini, or only app + sender + count
   until Kevin asks for the body?
3. **Pull or ambient.** Pull-based tools is the standing rule: "anything new?" as a tool call. Is
   there ANY ambient injection (a count in the live-session preamble), and does that survive the
   token-budget argument?
4. **Driving.** "Read that to me" hands-free is the killer use. Does the fleet Phase (driving
   detected) change what is offered, or is that a proactive raise the compulsion line forbids?
5. **2FA and secrets.** OTP codes appear in notifications. Blanket rule: never spoken, never sent
   to Gemini, never logged - or is reading an OTP aloud on request exactly the hands-free value?
   Decide deliberately.
6. **Tool budget.** One tool? Write the description in the answer.

## Answer

**Archived 2026-08-16 by Kevin: "archive this ticket, we dont need notifications for now."**
None of its six questions were answered. The premise check ran first and its findings are kept
below, because several are worth more than the ticket and several are defects rather than design.

All `traced` unless noted.

### The permission is ALREADY GRANTED - this ticket never opened a new aperture

`MediaNotificationListener` is a real, manifest-registered `NotificationListenerService` with
`BIND_NOTIFICATION_LISTENER_SERVICE` and the standard intent-filter
(`AndroidManifest.xml:194-201`). **Its body is empty** - it overrides nothing, and its KDoc says
verbatim "no notifications are parsed here" (`MediaNotificationListener.kt:10-12`). It exists only
because `MediaSessionManager.getActiveSessions` refuses to work without an enabled listener
`ComponentName` (callers: `NowPlayingController.kt:82,88`, `MusicController.kt:127-129`).

So LEGION has held **full notification-read access for as long as media controls have worked**.
`StatusBarNotification` appears nowhere in the tree - the capability is absent, the *permission* is
not. The ticket's cost story was wrong: it would have widened the use of an existing grant, not
requested a new one.

### Live defect, unrelated to this ticket: nothing ever checks the grant

`NowPlayingController.hasAccess(context)` (`:71-74`) is the app's only
`getEnabledListenerPackages` call and has **zero callers**. Nothing checks whether notification
access is granted and nothing asks the driver to grant it. The failure is silent by construction:
`NowPlayingController.init` swallows the `SecurityException` with "// Notification access not
granted yet." (`:90-92`), and `MusicController` logs and returns empty (`:130-133`).

**Consequence:** on a phone where the driver never granted notification access, media controls do
nothing and **the app never says why.** The house pattern for special access is the opposite -
check the grant, degrade in words, offer a one-tap Settings button (`AlarmScheduler.kt:41-42` +
`NotesRows.kt:172` for exact alarms; `NotesRows.kt:207-231` is the app's only
deep-link-to-Settings flow, and it is for POSTING notifications, not reading them). **Fourth orphan
found in one day.** Not fixed here; worth a small standalone ticket.

### The map's own framing sentence is false

The map says LEGION "almost exclusively READS". **`AmbientListener` ships today** (276 lines, wired
at `AriaForegroundService.kt:268`): local Vosk transcription of cabin conversation, a periodic
`SubAgent` pass deciding whether to react, and it **speaks unprompted** via
`ProactiveBus.requestSpeak` (`:245-248`). What makes it acceptable is the pattern any future
ambient sense should copy: **off by default**, explicit opt-in, and mute as a hard *listening*
gate rather than only a speaking gate (`AmbientListener.kt:41-46`, `:110-113`), re-checked again at
reaction time (`:238-243`), and explicitly excluded from `EpisodicTurn` (`:62-65`).

### Ticket item 4's premise was wrong

There is no "fleet Phase (driving detected)". `Phase` is the **conversation** phase -
`IDLE, CONNECTING, LISTENING, THINKING, SPEAKING` (`Phase.kt:8`). Drive detection is a private
local `driveStartedAt` inside a loop (`AriaForegroundService.kt:696,701`), not shared state.

### Carried forward to [the proactive switch](21-proactive-mode.md)

**There is not one gate, there are three.** `ProactiveGate.speakIfIdle` (`ProactiveGate.kt:20-29`:
onboarding-complete, not-busy, not-in-call, not-muted) is the shared one, with 11 raise sites
through it. But **`AmbientListener.kt:245` and `TelephonyController.kt:82` call
`ProactiveBus.requestSpeak` directly**, bypassing it and hand-rolling their own checks. Anything
added via the bus inherits nothing.

**Echo hazard for any future notification reader:** `ReminderAlarmReceiver` speaks a fired reminder
via the gate (`:70`) **and** posts a notification for the same item in the same method (`:127`). A
reader with no self-package filter hears LEGION's own reminder twice. LEGION posts on three
channels: `aria_channel` (id 1), `ledger_ingest_channel` (id 2), `reminders_channel` (id per item).

### Carried forward to any ticket that adds a tool

**`LiveToolbox.declarations()` emits 78 tools today**, all unconditional, re-sent on every session
open from three call sites. The baseline a new domain argues against is already large.
**Landmine:** `LiveToolboxDeclarationSetTest.kt:8-27` documents a shipped failure where five tools
were appended to `onboardingDeclarations()` instead of `declarations()` - dispatchable, thirteen
tests green, and invisible to the live model on device.

**Un-archive trigger:** Kevin wants the phone's notifications as a sense. The permission is already
there; only the reading is missing.
