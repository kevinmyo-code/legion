---
map: ledger-drive-ingestion
ticket: 07
title: "What is the app shell, and how does the app start itself?"
type: grilling
status: resolved
status-detail: ""
blockers: ["02"]
blocked-by: ["[[02-design-language]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# What is the app shell, and how does the app start itself?

## Question

`MainActivity.onCreate` calls `setContent` and nothing else. **Nothing anywhere starts
`AriaForegroundService`** - the only `startService` calls in the codebase are from inside the
service's own subsystems. So the Live session, all forty-odd voice tools, OBD, wake word,
proactives, and sync never execute. There is also no key-entry screen, since `FirstRunScreen` did
not port, so a Gemini key can only arrive through `BuildConfig` at build time.

This is the gate on the whole app being usable. Decide:

1. **Ignition.** Where the service starts: `MainActivity.onCreate`, `MidnightApplication`, or
   behind a user toggle. Which permissions must be held first (microphone, notifications, and on
   newer Android a foreground-service type), and what the app does when they are refused.
2. **Key entry.** The screen that takes a BYO Gemini key, validates it with the existing one-token
   ping, and stores it via `KeyVault`. Note the free-tier training disclosure Midnight AI shipped:
   decide whether that carries over, given there is no commercial tier here.
3. **First run.** What a stranger sees on a clean install with no key. Clone-and-run is a hard
   requirement (CLAUDE.md §2), so this path has to be genuinely walkable, not a dev shortcut.
4. **Navigation.** The shell that holds fleet, ledger, pantry, and settings. Shape follows from the
   design-language ticket; this ticket decides structure and back-stack behavior.
5. **Reachability of what exists.** `LedgerImportActivity` and `PantryImportActivity` are
   functional but `exported="false"` with nothing navigating to them. Fold them into the shell or
   replace them.
6. **Onboarding.** `ai/OnboardingFlow.kt` ported but its host UI does not exist and
   `AssistantIdentity` is placeholder copy. Decide whether onboarding is in scope for this pass or
   deferred, and say plainly what a first-run user gets if it is deferred.

---

## Resolution (2026-08-02, Kevin, 5 calls)

### 1. Ignition: explicit user toggle. BootReceiver DELETED.

Service starts when the user flips it on. Stops when they flip it off.

```
[ Assistant  (off) ]
   -> request POST_NOTIFICATIONS
   -> request RECORD_AUDIO
   -> startForegroundService(AriaForegroundService)

refused -> toggle stays off, states why
           ledger / pantry / fleet all still work
```

Permissions asked at the toggle, not at launch. A refusal has one clear meaning: assistant off,
nothing else affected.

**`BootReceiver` is deleted**, along with its manifest entry and `RECEIVE_BOOT_COMPLETED`.

FACT: it currently `startActivity(MainActivity)` on `ACTION_BOOT_COMPLETED`. That is car-launcher
behaviour that outlived the phone pivot. On a phone an app that opens itself every boot is hostile,
and it is the manufactured-return shape CLAUDE.md §7 prohibits.

Rejected: `MainActivity.onCreate` (opening the app to glance at a ledger would silently start mic
capture and a Live session, with no off switch short of force-stop) and `MidnightApplication.onCreate`
(process starts for reasons the user never initiated; with BootReceiver it runs permanently from
boot with no consent step at all).

### 2. Key entry - FACT correction first

**The 1-token validation ping EXISTS**, at `ai/GeminiKeyValidator.kt`, not `ai/KeyVault.kt`.
`KeyVault` is AES/GCM encrypt/decrypt only. **CLAUDE.md §3 attributes it to the wrong file** - fixed
in the same commit as this resolution.

`GeminiKeyValidator.check(key)` returns `KeyCheck.VALID | INVALID_KEY | NETWORK_ERROR`, via a
`maxOutputTokens = 1` `generateContent` call on `gemini-3.5-flash-lite`. The three-way split is
exactly what this screen needs: a typo and an aeroplane are different problems with different
recoveries.

```
paste -> GeminiKeyValidator.check
  VALID          -> CompanionProfile.saveGeminiKey (encrypted), proceed
  INVALID_KEY    -> "that key was rejected", stay on screen
  NETWORK_ERROR  -> offer save-and-verify-later, do not block
```

`CompanionProfile.saveGeminiKey` already encrypts via `KeyVault` and falls back to plaintext if the
Keystore is broken on the device - deliberate, a bricked key entry is worse than a plaintext one.

### 3. First run: key is OPTIONAL. No wall.

Install lands on the shell. Every tab live immediately.

**This works because most of the app needs no key at all:** deterministic ledger parsing, pantry,
OBD, saved places. Only the assistant and ticket 06's LLM fallback do.

The key is requested at the point of use:
- flipping the assistant toggle
- approving ticket 06's spend gate

Rejected: a key wall (blocks a stranger who only wants to import bank statements behind a Google
Cloud signup - hard to call clone-and-run genuinely walkable, and clone-and-run is a HARD
requirement per CLAUDE.md §2) and a skippable wall (frames the key as the normal path when for
ledger and pantry it is not).

### 4. The free-tier training disclosure CARRIES OVER, reworded

It was never about commercial tiers. It is a factual statement that Google's free API tier may use
submitted content to improve their models, and with BYO keys that content is **the user's own bank
statements and grocery receipts**.

Strip any wording implying a paid LEGION tier exists. Keep the substance. Put it **on the key
screen**, where the decision is actually made - not in an About page, which is present but absent
from the moment of consent.

```
Add your Gemini key

  LEGION talks to Google directly with your key.
  Nothing goes through a server I run.

  Note: on Google's free tier, content you send may be
  used to improve their models. That includes statement
  and receipt text.

  [ paste key ]                    [ Verify & save ]
```

### 5. Navigation: single activity, bottom nav, orphans absorbed

```
MainActivity  (LegionTheme, NOT MaterialTheme)
  NavHost
    fleet/       + fleet/places     <- was SavedPlacesActivity
    ledger/      + ledger/import    <- was LedgerImportActivity
    pantry/      + pantry/import    <- was PantryImportActivity
    settings/    + settings/key
```

Four top-level destinations: **Fleet, Ledger, Pantry, Settings.**

**Assistant is NOT a tab.** It is a mode, not a place: a global toggle in Settings plus a persistent
status affordance. Making it a tab would imply a screen it does not have.

Back stack is standard single-activity Compose: back pops within the tab, then to the start
destination, then exits.

FACT: `SavedPlacesActivity`, `LedgerImportActivity` and `PantryImportActivity` are functional but
`exported="false"` with nothing navigating to them. Their **content is already written** - only the
hosting changes, so absorbing them is cheap.

Rejected: a launcher screen starting them by intent (each needs its own theme setup and back
behaviour, no shared state, and ticket 05's scan `StateFlow` could not easily be observed from more
than one of them) and a nav drawer (hides three peer aspects behind a gesture).

### 6. Onboarding: DEFERRED. No screen at all.

`ai/OnboardingFlow.kt` stays unwired.

**Why.** It is a scripted conversation in the assistant's voice, and **that voice does not exist** -
`ai/AssistantIdentity.kt` is placeholder copy by its own doc comment. Wiring the host UI now means
writing the assistant's register by accident, inside a ticket scoped to app structure. That decision
deserves its own effort.

**What a first-run user gets instead, stated plainly:** the shell, immediately usable, all four tabs
live, with the key screen reachable when something needs it. No conversation, no wizard, no wall.

`OnboardingState.isComplete` keeps meaning "has a Gemini key". That was a placeholder; with an
optional key it is now **honest**, because the key genuinely is the only gate on assistant features.

### Specified, not asked - follows directly

- **`MainActivity` switches `MaterialTheme` -> `LegionTheme`.** The Instrument theme was built in
  ticket 02 and is currently unused by the only screen that exists.
- Manifest deletions: `SavedPlacesActivity`, `LedgerImportActivity`, `PantryImportActivity`,
  `BootReceiver`, and the `RECEIVE_BOOT_COMPLETED` permission.
- **Render the five previews in `ui/theme/ThemePreview.kt` before building screens on the theme.**
  It compiles and has never been drawn.

### What this ticket does NOT settle

- The assistant's actual voice / `AssistantIdentity` copy. Its own effort.
- What any screen looks like inside a tab. **Tickets 08 and 09.**
- Whether the Live session prewarm behaviour changes now that ignition is user-initiated.
