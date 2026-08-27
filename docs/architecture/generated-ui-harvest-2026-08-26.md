# Harvest: what ui/generated/ knew before it was deleted

> **Why this file exists.** 596 lines under ui/generated/ solved the problem of rendering an
> arbitrary record shape into Compose without crashing, and validating it before committing. ADR
> [[0039-per-aspect-typed-tables]] retires them and backend-erp phase 6 deletes them. This is the
> transferable half, written down so the generated-ui renderer's builder does not rediscover it.
> Source: generated-ui ticket 07.
>
> **The three source paths in this document are deliberately NOT in backticks.** `tools/docs_check.py`
> resolves backticked package-relative Kotlin paths and fails when one does not exist. These files
> are scheduled for deletion, so backticking them would plant a check failure timed to phase 6.

| File | Lines | Role |
|---|---|---|
| ui/generated/GeneratedFormScreen.kt | 293 | One editor per field type, two-layer validation |
| ui/generated/GeneratedDetailScreen.kt | 161 | Rendering an arbitrary record's fields |
| ui/generated/GeneratedListScreen.kt | 142 | Rendering an arbitrary record type as a list |

---

## 1. The field-type-to-editor mapping

Thirteen `FieldType` values, and the `when` over them is **exhaustive with no `else` branch**. That
is the single most transferable decision here: adding a fourteenth type is a compile error at every
render site, not a silent blank on a screen. Keep that property in the new renderer even though its
input is a UI schema rather than a data schema.

| Field type | Editor | Display form | Note worth carrying |
|---|---|---|---|
| TEXT, LOCATION | text field | raw string | LOCATION had no map picker; it was a string, honestly |
| NUMBER, RATING | text field, `toDoubleOrNull()` | `Double.toString()` | An unparseable keystroke writes `null`, not a stale prior value |
| MONEY_CENTS | text field labelled "(dollars)" | sign + `abs/100` + zero-padded remainder | **Displays dollars, stores `Long` cents.** `Math.round(dollars * 100.0)` at the boundary. CLAUDE.md section 4 rule 3 lives or dies in this one line |
| DATE, DATETIME | text field of epoch ms + a NOW button | `SimpleDateFormat("MMM d, yyyy")` | Raw epoch ms in a text box was a placeholder and always read as one. **Do not carry this forward** |
| BOOLEAN | switch | "YES" / "NO" | |
| CHOICE | radio group from `FieldConfig.choiceOptions` | string | Empty options renders "NO OPTIONS CONFIGURED" in quarantine colour, not an empty box |
| MULTI_SELECT_CHOICE | checkbox group | - | Value is `List<String>`; add/remove by set arithmetic |
| REFERENCE | radio group over candidate records | "Record #id" | Candidates loaded per-field in its own `LaunchedEffect`. Labelling a reference by id rather than by the target's title field is a **known weakness**, not a pattern |
| PHOTO | camera launcher, `EnginePhotoStore.save` | "PHOTO ON FILE" | Stores a path in the payload, never bytes |
| COMPUTED | **none - never user-editable** | value, or its error in words | Filtered out at the caller AND unreachable-branched inside the editor. Belt and braces on purpose |

### The four "unknown shape" cases, and what each one renders

None of them throws, and none renders empty. This is the part to copy verbatim in spirit:

1. **Unconfigured choice field** - "NO OPTIONS CONFIGURED", quarantine colour.
2. **Unconfigured reference field** - "NOT CONFIGURED".
3. **Configured reference with no targets yet** - "NO CANDIDATE RECORDS YET", faint, *not*
   quarantine. A different sentence for a different condition; an empty result and a broken config
   are not the same fact. This mirrors CLAUDE.md section 1's "unreadable and empty are different
   sentences".
4. **Record not found** - "RECORD NOT FOUND", quarantine colour, rather than a blank scaffold.

Distinct loading and empty states throughout: `loaded` is its own boolean, never inferred from an
empty list. And "no records yet" versus "no records match '<query>'" are separate strings.

---

## 2. The validation layering

Two layers, deliberately unequal, and the split is the lesson.

**Layer 1 - `engine/GeneratedFormValidation.kt` (pure).** No Compose import, no DAO, no
`RecordStore` call. A plain function of already-collected form state, so it unit-tests without
Robolectric. Catches only what the form can know by itself:

- a required field absent or blank
- a value whose runtime Kotlin type does not match its field type (`shapeMatches`)

It skips COMPUTED entirely and returns per-field errors in field order. Empty list means clean
enough to *attempt* a submit.

**Layer 2 - `engine/RecordStore.kt` (authoritative).** Only a real DAO read can answer whether a
reference target still exists or a field was deleted, so those checks live at the write door and
nowhere else. The form deliberately does **not** duplicate them.

The load-bearing property: **layer 1 never claims to be sufficient.** A form that passes it can
still come back `WriteResult.Failure`, and the screen renders that failure the same way it renders a
validation error - a worded banner, "COULD NOT SAVE: <reason>", never a raw exception, never a
silent no-op. Same rule as CLAUDE.md section 4: a check that could pass vacuously is not the gate;
the gate is at the write door.

**Carry this shape.** Cheap offline check for fast feedback, authoritative check at the single write
door, both surfaced in the same words. What must not happen is layer 1 growing database access to
"help" - that is how two validators start disagreeing.

---

## 3. Provenance and computed errors on a generated surface

Two rules that were applied here specifically because the surface was generated, and generated
surfaces are exactly where a rule gets forgotten:

- **Provenance in words, never colour alone.** `provenanceWord()` maps to "DETERMINISTIC", "LLM
  RECONCILED", "UNRECONCILED - NOT VERIFIED", "HAND-ENTERED". CLAUDE.md section 4 rule 7 explicitly
  says every surface rendering a provisional row says so *in words*. A generated screen is a surface.
- **A computed-field error is rendered, not swallowed.** `renderFieldValue` returns the error string
  plus an `isError` flag, and the detail screen prints it in quarantine colour rather than showing a
  bare dash. Section 4 rule 6 read onto a generated surface: an unrecognised value is a hard, visible
  failure, never a skip.

---

## 4. The plugin escape hatch

`GeneratedDetailScreen` takes `pluginOverride: (@Composable (recordId: Long) -> Unit)?`. When
non-null it renders and **returns immediately** - the generated screen below is never composed, so a
record type with a hand-built native detail (a car with live OBD) pays nothing for the fallback it
does not use. This is ADR 0035's hands-path rule with an opt-out that costs zero.

It had no production caller as of writing; only `GeneratedScreensTest` exercised it. Worth carrying
as a pattern, worth knowing it was never load-bearing in practice.

---

## 5. What does NOT transfer

Listed explicitly so nobody ports it by habit.

- **The input is a DATA schema.** Every screen here reads `field_defs` and derives the UI from field
  types. The generated-ui renderer's input is a **UI schema** the model emits. Do not carry the
  `FieldDef`-scan shape; carry the exhaustive-`when` discipline that surrounds it.
- **Direct DAO access from the composable.** Each screen builds `CarDatabase.getDatabase(context)`
  and queries inside `LaunchedEffect`. Acceptable at this scale, wrong for a renderer whose numbers
  must come from tool results (ticket 02's binding contract), not from its own reads.
- **Epoch-ms text boxes for dates.** A placeholder that shipped. The new renderer needs a real
  picker.
- **"Record #<id>" as a human label.** Used for reference options and linked-record rows. Titles
  should come from the target's own title field.
- **`titleFor` duplicated from `WidgetDataSource`.** Copied because the original was `private`. Two
  copies of a title rule is one more than the new renderer should have.
- **Client-side filter and sort over a fully-loaded list**, with `PAGE_SIZE = 20` growing an
  in-memory window. Justified at 400 rows on one phone. It is not a pattern to carry to a
  server-backed multi-client world where the row count is not bounded by one household.
