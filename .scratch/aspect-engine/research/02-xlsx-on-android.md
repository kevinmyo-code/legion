---
map: aspect-engine
ticket: "02"
kind: research
date: 2026-08-23
---
# xlsx on Android: library, size, embedded validation

Research for ticket 02. Sources are primary where they exist; mobile-app runtime behavior is
thinly documented and several points are marked NOT ESTABLISHED with hands-on verification owed.

## 1. Library options

| Library | Reads xlsx | Writes xlsx | Writes dataValidation | Android cost | Maintained |
|---|---|---|---|---|---|
| fastexcel (dhatim) | Yes (`fastexcel-reader`, streaming, cell content only) | Yes | Yes: `DataValidation`, `ListDataValidation`, `ListFormulaDataValidation`, `CustomDataValidation` classes in writer source | Plain Java 8+, small dep tree, no awt | Active: 979 commits, writer 0.20.2 / reader 0.18.4 |
| Apache POI via poi-on-android (centic9) | Yes (XSSF) | Yes | Yes (POI `XSSFDataValidation`) | Shaded fat jar, sample minSdk 26, `java.awt` gaps break column-width/images, multidex-scale method count | Active but "pre-built releases may be somewhat outdated" |
| android5xlsx (andruhon) | Yes | Yes | POI 3.x era | Repacked old POI 3.12 jars | Stale |
| poi-android (SUPERCILEX) | Yes | Yes | POI | JitPack wrapper | Low activity |

Sources:
- fastexcel README: reader "only reads cell content. It discards styles, graphs"; Java 8+;
  worksheet `protect(...)` and `protectWithViewPassword` exist. https://github.com/dhatim/fastexcel
- fastexcel writer validation classes (DataValidation.java, ListDataValidation.java,
  ListFormulaDataValidation.java, CustomDataValidation.java, DataValidationErrorStyle.java):
  https://github.com/dhatim/fastexcel/tree/master/fastexcel-writer/src/main/java/org/dhatim/fastexcel
- Open issue asking for a convenience dropdown API (does not negate the existing validation
  classes; it asks for sugar): https://github.com/dhatim/fastexcel/issues/580
- poi-on-android: shaded jar, minSdk 26 sample, awt limitation: https://github.com/centic9/poi-on-android
- android5xlsx: https://github.com/andruhon/android5xlsx
- poi-android: https://github.com/SUPERCILEX/poi-android
- Multidex background (POI-scale libs push past 64K refs; minSdk 21+ handles it natively):
  https://developer.android.com/build/multidex

Exact APK size / method-count numbers for the POI shaded jar: NOT ESTABLISHED from sources.
poi-on-android README says shading "keeps size at bay" but publishes no figure. Hands-on
measurement owed if POI is ever chosen. fastexcel publishes no size figure either, but its dep
tree is a fraction of POI's by construction (single-purpose writer + streaming reader).

## 2. Do Sheets / Excel mobile enforce embedded validation?

- Google Sheets, Office editing mode (desktop web, best-documented case): protected sheets and
  ranges are UNAVAILABLE; the menu item disappears and Google says to convert to a native Sheet
  to use them. https://support.google.com/docs/answer/1218656
- Whether the Sheets ANDROID app enforces embedded xlsx dataValidation rules during Office
  editing: NOT ESTABLISHED from primary sources. No Google doc states it either way. Hands-on
  verification owed (A25, real file, out-of-range entry).
- Excel for Android: cannot create or edit data validation rules ("Data validation is not
  available for Excel Mobile for Android"): community/MS answers, e.g.
  https://learn.microsoft.com/en-us/answers/questions/4935429/excel-for-android-data-validation-is-not-shown-on
  and https://techcommunity.microsoft.com/discussions/excelgeneral/implementing-excel-data-validation-on-android/2704031
  Whether EXISTING rules are enforced (dropdown offered, bad input rejected) on Android:
  community reports say dropdowns render, but nothing authoritative. NOT ESTABLISHED; hands-on owed.
- Preservation on save: Google states Office-editing changes "are saved in the original Excel
  format" (https://support.google.com/docs/answer/9331167,
  https://workspaceupdates.googleblog.com/2019/04/office-editing.html). Whether the
  dataValidation XML parts survive a Sheets resave byte-meaningfully: NOT ESTABLISHED; hands-on owed.

**Plain statement, as the ticket demands:** treat validation-in-the-file as decoration. Nothing
establishes enforcement on either mobile editor, and the import gate must carry everything
regardless. Embedded validation is a UX courtesy where it happens to render, never a defense.

## 3. Does Sheets save back as xlsx or convert?

- Office editing (2019 launch, web): "edit, comment, and collaborate on Office files ... changes
  auto-saved to the file in its existing Office format."
  https://workspaceupdates.googleblog.com/2019/04/office-editing.html
- Office editing became the DEFAULT for Office files in Drive on the web (Oct 2020):
  https://workspaceupdates.googleblog.com/2020/10/office-editing-default-google-drive-docs.html
- Google's help page confirms in-place: "Open an Excel file from Drive and edit it in Sheets.
  Any changes you make are saved in the original Excel format."
  https://support.google.com/docs/answer/9331167
- Android: Office editing shipped to mobile apps (rollout reported Sept 2019 for Android;
  the help pages are web-worded and no Android-specific page was found stating it verbatim).
  Traced for web, REASONED for Android. Conversion to a native Sheet happens only via an explicit
  "Save as Google Sheets" action, and the converted copy is a NEW file; the xlsx bytes remain.
  Hands-on confirmation on the A25 owed before the read-back path is declared safe.

## 4. Values-not-formulas and sheet protection

- Writing computed columns as literal values with a header note: trivially possible in any
  writer; a value cell is the default. No source needed.
- fastexcel can protect a worksheet from editing (`protect(...)` in README,
  https://github.com/dhatim/fastexcel). xlsx sheet protection is a workbook-level advisory flag,
  not encryption.
- Sheets in Office editing mode does NOT expose protected sheets/ranges
  (https://support.google.com/docs/answer/1218656) and Google's own docs list "protected ranges"
  among the features that require converting to a native Sheet
  (https://support.google.com/docs/answer/9406611). Whether Sheets mobile HONORS (blocks edits
  to) an xlsx protected sheet it cannot display: NOT ESTABLISHED; hands-on owed. Assume not honored.
- Verdict: write computed fields as values with a note; set sheet protection anyway (free, honest
  in Excel desktop); rely on the import gate to reject edits to computed columns.

## 5. Precision: Long cents in xlsx

- xlsx number cells are IEEE 754 doubles; integers are exact up to 2^53 = 9,007,199,254,740,992.
  https://en.wikipedia.org/wiki/Numeric_precision_in_Microsoft_Excel
- Excel's engine displays/rounds to 15 significant digits, so integers above ~1e15 get mangled
  on EDIT even though the file can store 17 digits.
  https://learn.microsoft.com/en-us/answers/questions/4898851/why-does-excel-numeric-values-lose-precision-but-h
  https://endjin.com/blog/2022/07/excel-data-loss-ieee754-and-precision
- Integer cents stay exact if |cents| < 1e15, i.e. under 10 trillion dollars. Personal-ledger
  magnitudes are ~9 orders of magnitude below the cliff. Zero round-trip risk.
- Dollars-with-decimals (e.g. 12.34) is a binary fraction: NOT exact, and reconciliation-by-
  equality would break. Rejected.
- Text cells are exact but read back as strings, break SUM for the human looking at the sheet,
  and invite apostrophe/locale mangling. Rejected.
- **Convention: plain number cell holding integer cents, column header saying "(cents)".**
  Optionally a display format of `#,##0` to stop editors reformatting; never a currency format
  that tempts a decimal.

## Test implications (PdfBox precedent)

fastexcel is plain-JVM, ships no Android assets, and touches no `android.*` API. Unit tests can
write and read xlsx byte streams on the bare JVM with no Robolectric, unlike PdfBox (which needs
Robolectric to shadow AssetManager for its bundled fonts). REASONED from its dependency shape,
not yet run in this repo; the first spike test should confirm. POI-shaded would carry real
Robolectric/desugaring risk and its awt gaps are exactly the class of thing only a real run catches.

## Assumptions ledger

| Claim | Tag |
|---|---|
| fastexcel writes list/custom data validation (classes exist in writer source) | traced |
| fastexcel reader reads cell content only, discards styles | traced |
| fastexcel worksheet protect() exists | traced |
| fastexcel actively maintained, writer 0.20.2 | traced |
| poi-on-android shaded jar, sample minSdk 26, awt gaps | traced |
| POI shaded jar APK size / method count figures | NOT ESTABLISHED |
| Sheets web Office editing saves in original xlsx format | traced |
| Office editing default in Drive web since Oct 2020 | traced |
| Sheets ANDROID app edits xlsx in place | reasoned (rollout reported; no verbatim Google doc found); hands-on owed |
| Sheets Office editing hides protected sheets/ranges | traced |
| Sheets mobile enforces embedded xlsx dataValidation | NOT ESTABLISHED; hands-on owed |
| Sheets resave preserves dataValidation XML parts | NOT ESTABLISHED; hands-on owed |
| Excel Android cannot create/edit data validation | traced (MS Q&A/community) |
| Excel Android enforces existing validation rules | NOT ESTABLISHED; hands-on owed |
| Integer cents exact in xlsx below 2^53, edit-safe below 1e15 | traced |
| Decimal dollars inexact in IEEE doubles | traced |
| fastexcel tests run on bare JVM without Robolectric | reasoned; confirm in first spike |
