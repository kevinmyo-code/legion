---
map: aspect-engine
ticket: "02"
title: "xlsx on Android: library, size, and embedded validation"
type: research
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
