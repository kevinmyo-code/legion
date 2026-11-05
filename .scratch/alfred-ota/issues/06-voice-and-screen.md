---
map: alfred-ota
ticket: "06"
title: "Updates by voice and on a screen"
type: build
status: built
status-detail: "built 2026-11-05 on feat/alfred-ota-phone, JVM suite green. Owed: Kevin's run (ticket 07); Kevin to ratify the payload ceiling raise"
blockers: ["05"]
blocked-by: ["[[05-phone-updater]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Updates by voice and on a screen

## What

- **Voice tool `app_update`**, with actions `check`, `install` and `whats_new`.
  - `install` runs only after Kevin's yes in that same turn.
  - The result says in words what did and did not happen (§7 outcome verbs).
- **Settings screen "Updates".** ADR 0035: every voice capability has a hands path, and both call the same controller. The screen shows:
  - the current version
  - the latest available version and its notes
  - Check now and Install buttons
  - an "Allow mobile data" toggle
  - "Alfred's wording: pack v<N> from engine|cache|built-in"
- **User guide.** Add copy for `docs/voice.html` in `tools/voice_guide_copy.py`.

## Verification

- JVM tests cover the tool's refusals.
- The voice guide script passes.

## Built (2026-11-05, branch `feat/alfred-ota-phone`)

- `service/AppUpdateToolbox.kt`: one `app_update` tool, actions `check`, `whats_new`, `install`
  (`confirmed=true` only after a yes). `success` is true only when PackageInstaller took the file.
  Declared in `LiveToolbox.declarations()`, listed in `MUTATING_TOOLS`.
- `ui/settings/UpdatesScreen.kt` + `UpdatesViewModel.kt` (Settings, Updates; route
  `settings/updates`; the notification opens it): installed version, newest and its notes, Check
  now, Install, Allow mobile data, Allow installs, "Alfred's wording: pack vN from
  engine|cache|built-in". Same `UpdateController` as the tool (ADR 0035).
- `tools/voice_guide_copy.py` copy and a "LEGION itself" group; `voice_guide.py` re-run.
- **The Live payload ceiling moved** for the new declaration; see `LiveSetupPayloadSizeTest`'s
  dated entry. Kevin ratifies or a description gets trimmed.
- Verification: the tool's refusals, JVM (`AppUpdateToolboxTest`); the voice guide script passes.
