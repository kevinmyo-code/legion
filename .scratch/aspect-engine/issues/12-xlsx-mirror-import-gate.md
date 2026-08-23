---
map: aspect-engine
ticket: "12"
title: "The xlsx mirror and its import gate"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-23: workbook per aspect, debounced export, reconciled read-only, gate carries integrity."
blockers: ["01", "02", "03"]
blocked-by: ["[[01-drive-folder-write-back]]", "[[02-xlsx-on-android]]", "[[03-engine-schema]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# The xlsx mirror and its import gate

## Question

Charter decision 5: one .xlsx per table in a user-visible Drive folder; hand edits return through
a validating import gate. With tickets 01 (write-back) and 02 (library/validation) answered,
decide:

1. **File layout.** One workbook per aspect with a sheet per record type, or one file per record
   type? Column order, header row contract, the id column (hidden? protected?), provenance and
   computed columns read-only.
2. **Export cadence.** On every write (chatty), debounced, on app background, or manual "sync
   now"? What the mirror is PROMISED to be: an audit surface with bounded staleness, stated in
   words in the UI.
3. **Change detection.** How the app knows a file was hand-edited (lastModified via SAF? content
   hash?) and which direction wins when both sides changed since last export - define the
   conflict rule now, in the app's favor or the file's, never silent.
4. **The import gate.** Row-level validation against field defs, reference existence, money
   parsing (per ticket 02's cell convention), quarantine UX for rejected rows (shown in words,
   fixable, re-importable). A hand-edited row is an unverified source; the sec 4 posture applies.
5. **Scope of editability.** Are all record types hand-editable, or do gated aspects (ledger
   reconciled rows) export read-only sheets? Recommend: reconciled rows read-only in the mirror -
   editing a reconciled row by hand would un-reconcile it, and that needs the app's own UI where
   the gate can speak.

## Answer

Resolved 2026-08-23 (Kevin, batched grilling; research tickets 01 and 02 feed this).

1. **Layout:** one workbook per aspect, one sheet per record type. Header row contract fixed; id
   column present and protected; provenance and computed columns read-only.
2. **Export:** debounced after writes plus on app background; the folder screen states the
   staleness promise in words. Writes use SAF `rwt` full-rewrite with read-back hash verify;
   mismatch quarantines the mirror (ticket 01's caveats made binding).
3. **Validation in the file is decoration** (ticket 02): generated anyway for the honest editor,
   but the **import gate carries all integrity**: row-level validation against field defs,
   reference existence, integer-cents money cells, quarantine UX in words, fixable and
   re-importable.
4. **Reconciled rows are read-only in the mirror.** DETERMINISTIC and LLM_RECONCILED rows export
   protected; un-reconciling is an in-app act where the gate can speak. Unreconciled rows and
   user-authored aspects are hand-editable.
5. Change detection: lastModified plus content hash per file. The conflict rule lives in ticket
   13's answer (row-level merge).

Build work: [Build the mirror and sync](20-build-mirror-sync.md). The on-A25 write-back probe is
owed there before anything depends on it.
