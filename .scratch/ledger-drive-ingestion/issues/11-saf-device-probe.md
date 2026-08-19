---
map: ledger-drive-ingestion
ticket: 11
title: Run the 15-minute SAF probe on a real device
type: task
status: open
status-detail: "mostly done - steps 1-6 run and passed, steps 7-9 blocked on transport"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Run the 15-minute SAF probe on a real device

## Question

Nothing to decide. This is the manual work that converts the access model's central claim from
`traced` to `tested`, and it blocks nothing except confidence.

[Can SAF actually read a Google Drive folder?](01-saf-drive-folder-feasibility.md) resolved to
"build on SAF", but its crux - **does a picked tree's `listFiles()` include files added AFTER the
grant** - was answered by tracing four layers of framework and Drive bytecode, not by running
anything. `adb devices` was empty for the whole research session. The entire Drive-folder feature
rests on that one behavior.

`../research/01-saf-drive-folder-findings.md` section "The minimal experiment that settles #2"
contains paste-ready probe code and the exact steps. In outline:

1. Install the probe on the Oppo A17K. **Check its Android version first** - the provider only
   advertises tree support at `SDK_INT >= 30`, so a device below that proves nothing about the
   crux, only about the gate.
2. `ACTION_OPEN_DOCUMENT_TREE`, pick a Drive folder, `takePersistableUriPermission`.
3. `listFiles()` and record the count.
4. Upload a new PDF to that folder from another device or the web.
5. `listFiles()` again **without re-picking**. Record whether the new file appears, and how long it
   takes to show up.
6. Reboot the phone. Confirm the persisted grant still resolves and still enumerates.
7. Read bytes from one PDF via `openInputStream`. Confirm it works, and repeat with the device in
   airplane mode to observe the offline failure mode.

Record per-document metadata actually returned (id, size, last-modified, and whether anything
hash-like appears), since the ingested-file ledger's identity choice depends on it.

**Deliverable:** append the observed results to the findings file under a `## Device probe` heading,
tagged `tested`, and correct any `traced` claim the device contradicts. If the crux comes back NO,
say so loudly - that invalidates the access model and reopens the resolved research ticket.

## Progress (2026-08-01)

**Probe app is built and waiting on hardware.** Started this session; `adb devices` was empty, so
nothing has run.

- Project: `<scratchpad>/safprobe/`, package `com.kevin.safprobe`, `minSdk 30`, built OUTSIDE the
  legion repo as the research file requires. Framework APIs only, **zero dependencies**, so no
  result can be blamed on a library version.
- APK: `<scratchpad>/safprobe/app/build/outputs/apk/debug/app-debug.apk` (2.5 MB), `assembleDebug`
  green. Note for anyone rebuilding: AGP 9 needs `android.builtInKotlin=false` and
  `android.newDsl=false` in `gradle.properties`, same as the legion repo, or
  `org.jetbrains.kotlin.android` collides with AGP's own `kotlin` extension.

Two deliberate deviations from the research file's paste-ready code, both additive:

1. **Null projection instead of a fixed six-column one.** The fixed projection would hide any
   column the provider actually returns, and the whole point of the metadata question is whether
   anything hash-like appears. The probe logs `columnNames` plus every column of every row.
2. **Output mirrored on screen, not logcat only.** Step 5 is performed on a laptop and the phone
   may not be tethered at that moment. Each LIST is timestamped so step 6's sync latency is
   measurable. `adb logcat -s SAFPROBE` still works.

Remaining is entirely manual and needs Kevin: Drive prep (`LegionProbe` folder, two PDFs, browse
into it once from the Drive app), phone connected with USB debugging, then steps 1-9.

## Outcome (2026-08-02)

**Steps 1-6 ran and PASSED. The crux holds: a file uploaded after the grant appeared in
`listFiles()` with no re-pick.** Full results, metadata and hazards appended to
`../research/01-saf-drive-folder-findings.md` under `## Device probe`, and the verdict at the top
of that file corrected from `traced` to `tested`. The access model is not void; ticket 01 stays
resolved and 03/05/08 do not reopen.

Three things the trace did not predict, all filed in the findings:

1. **Latency.** The new file was invisible for at least 2m36s and appeared only after the Drive app
   was opened on the phone. The two variables were not isolated. Design for "a scan may legitimately
   find nothing new", do not treat it as an error.
2. **A stale-empty listing has no signal.** `extras` was `Bundle[EMPTY_PARCEL]` on every query, no
   `loading` key ever, yet the picker did serve "No items" for a folder that had five files. A
   single `COUNT=0` cannot be read as "folder is empty".
3. **`last_modified` is per-file upload time**, which the first listing's five identical values
   made look like a folder-wide stamp. Batch upload artefact, resolved by the sixth file.

**Steps 7, 8 and 9 were not run** (reboot persistence, airplane-mode listing, offline byte read).
USB never enumerated on Kevin's machine - the phone did not appear in `Get-PnpDevice` at all across
a cable swap and an MTP-mode change - so the session ran over wireless ADB, and both remaining tests
sever that transport. They need a working USB cable, or a human reading the probe's on-screen
output. **The offline failure mode is still untested and sub-question 4 stays `traced`.**

The probe app is still installed on the device with its grant intact, so those three steps can be
picked up later without redoing steps 1-6.
