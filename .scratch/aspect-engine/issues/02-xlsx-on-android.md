---
map: aspect-engine
ticket: "02"
title: "xlsx on Android: library, size, and embedded validation"
type: research
status: resolved
status-detail: "fastexcel recommended; integer-cents number cells; embedded validation is decoration, gate carries all"
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# xlsx on Android: library, size, and embedded validation

## Question

The mirror format is .xlsx with embedded data-validation rules (charter decision 5). The app must
**write** xlsx (one file per table, validation dropdowns/ranges generated from field definitions)
and **read** it back through the import gate. Establish, from primary sources:

1. Library options on Android: Apache POI (and the poi-on-android repackagings), fastexcel,
   others. For each: reads AND writes xlsx? Supports writing `dataValidation` rules? APK size /
   method count / minSdk cost? Maintained?
2. Does the Google Sheets app, opening an .xlsx from Drive, actually *enforce* embedded
   data-validation rules during editing, and does it preserve them on save? Same question for
   Excel mobile. If neither enforces, validation-in-the-file is decoration and the import gate
   carries everything - say so plainly.
3. Editing an .xlsx in the Sheets app: does it save back as xlsx in place, or convert to a native
   Sheet (which has no bytes and would break the read-back path)? This is load-bearing - cite it.
4. Formula cells: can we write computed-field columns as values-with-a-note rather than formulas,
   and mark them read-only (sheet protection)? Is sheet protection honored by Sheets mobile?
5. Precision: money is Long cents. Round-trip risk of xlsx numeric cells (IEEE doubles) vs storing
   money as integer cents in a plain number cell vs text. Recommend the cell convention.

Findings go to `.scratch/aspect-engine/research/02-xlsx-on-android.md`. PdfBox precedent says
heavyweight Java libs can need Robolectric in tests - note test implications.

## Answer

Resolved 2026-08-23. Full findings with citations and assumptions ledger:
`.scratch/aspect-engine/research/02-xlsx-on-android.md`.

1. **Library: fastexcel (dhatim), writer + reader.** Writer ships `ListDataValidation` /
   `ListFormulaDataValidation` / `CustomDataValidation` and worksheet `protect(...)`; reader is a
   streaming cell-content parser, which is all the import gate needs. Plain Java 8+, no awt, no
   Android assets, so unit tests should run on the bare JVM without Robolectric (reasoned - confirm
   in the first spike). POI-shaded (poi-on-android) is the fallback if fastexcel's validation API
   proves insufficient; it costs a fat shaded jar, minSdk 26 sample baseline, and awt gaps.
2. **Money cell convention: plain number cell holding integer cents**, header labelled "(cents)",
   number format `#,##0`. Integers are exact in xlsx doubles below 2^53 and edit-safe below 1e15;
   decimal dollars are inexact binary fractions and would break gate equality; text cells break
   human SUM and invite locale mangling.
3. **Validation enforcement verdict: treat embedded validation as decoration.** No primary source
   establishes that Sheets mobile enforces xlsx dataValidation in Office editing; Excel Android
   cannot even create/edit rules; Sheets Office editing hides protected sheets/ranges outright
   (traced). Whether Sheets Android edits xlsx in place (reasoned yes, per Office editing rollout)
   and whether resave preserves validation parts are NOT ESTABLISHED - hands-on runs on the A25
   owed. The import gate carries ALL integrity, per §4; embedded rules are a courtesy where they
   happen to render.
