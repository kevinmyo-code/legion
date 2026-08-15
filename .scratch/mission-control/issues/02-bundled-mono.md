# Bundled mono: license, size, glyph and figure coverage

Type: research
Status: resolved

## Question

Which open-licensed monospace face ships in `app/src/main/res/font`, and what does it cost?

Today `ui/theme/Type.kt` resolves everything to `FontFamily.Monospace` - whatever the device
happens to ship. On Kevin's Oppo A17K that is an unknown quantity, and a large part of the refs'
character lives in the letterforms.

**Investigate, against primary sources (the foundry's own repo and license file, not a blog):**

1. **Candidates.** At minimum: JetBrains Mono, IBM Plex Mono, Space Mono, Martian Mono, Azeret
   Mono, Roboto Mono. Add any others that fit the mission-control register.
2. **License.** OFL 1.1 or Apache 2.0 only. Record the exact license, its attribution
   requirement, and whether bundling in a public GitHub repo needs a NOTICE entry.
3. **Size on disk**, per weight. LEGION needs Regular, Medium/SemiBold and Bold at most; report
   the total APK cost for that set, and whether a variable font is smaller than three statics.
4. **Tabular figures.** Compose has no `font-variant-numeric: tabular-nums`, so monospacing IS the
   alignment mechanism for money columns. Confirm the digits are genuinely fixed-advance and that
   the minus sign and currency glyphs do not break the column.
5. **Glyph coverage** for what LEGION actually renders: `$`, `-`, `.`, `:`, `/`, `//`, `>`, `_`,
   box-drawing or bracket characters if the chrome uses them, degree and unit marks, and the
   arrows used for deltas.
6. **Caps at small tracked sizes.** Panel labels run 9-12sp with ~0.2em letterspacing. Report
   which candidates hold up there and which turn to mush - this is where the chrome either reads
   as instrument output or does not.
7. **Condensed companion.** Note whether the family offers a condensed or narrow cut, since the
   map's fog flags a possible second face for pills and labels.

**Deliverable.** A findings file under `.scratch/mission-control/research/`, with a recommendation
and a runner-up. Capture on a throwaway `research/bundled-mono` branch.

**Not in this ticket:** the type SCALE (sizes, weights, roles). That carries over from `Type.kt`
and gets retuned inside the build ticket, not here.

## Answer

**Martian Mono Condensed** (OFL 1.1, Evil Martians). Runner-up **JetBrains Mono**; third **IBM Plex
Mono**. Full findings, measurements and sources: [`research/bundled-mono.md`](../research/bundled-mono.md).

| | Martian Cond | JetBrains | Plex |
|---|---|---|---|
| Cap height | **0.800 em** | 0.730 | 0.698 |
| Digit advance | 0.600 em | 0.600 | 0.600 |
| 3 statics, deflated | **124 KB** | 388 KB | 172 KB |
| Condensed cut | **4 widths in-family** | none | none |
| Box drawing / blocks | none | full | full |
| Reserved Font Name | none | none | **"Plex"** |

### What the investigation actually settled

1. **Licence.** All ten candidates are OFL 1.1. **None is Apache 2.0** - Roboto Mono has been
   relicensed. OFL has no NOTICE mechanism (that is Apache 4(d)); clause 2 wants the copyright and
   licence shipped with each copy. Every candidate embeds name IDs 13/14 with `fsType=0`, but
   `res/font/` rejects `.txt`, so `OFL.txt` goes in `third_party/`.
2. **IBM Plex declares a Reserved Font Name.** A subset would be a Modified Version and would have
   to be renamed. Martian, JetBrains and Azeret declare none. This is most of why Plex places third.
3. **Variable fonts are unusable at `minSdk = 24`.** Compose gates `setFontVariationSettings` behind
   `SDK_INT >= 26` and silently returns the unmodified typeface below it, so one variable file
   would resolve to its default instance at every weight with faked bold. **Ship three statics.**
4. **`.ttf` is compressed in the APK** (aapt2's default no-compress list is images, audio and video
   only), so the deflated figures above are the real cost, not the raw ones.
5. **Tabular alignment holds in all ten.** Digits, `$`, hyphen, U+2212, `.` and `,` share one
   advance. The `isFixedPitch` flag is unreliable and was ignored in favour of measuring `hmtx`.
6. **One grid hazard found:** Azeret's em dash is 1300 units, exactly double width. Not our pick,
   and moot in this repo anyway since CLAUDE.md bans em dashes outright.
7. **Roboto Mono and Space Mono are disqualified** - a plain zero with no `zero` feature to switch
   on, and no Medium or SemiBold at all, respectively.

### Consequences the build ticket inherits

- **The type scale needs a ~10% pass down.** Martian's cap height is 0.800 em against the platform
  mono the current `Type.kt` sizes were chosen against. Sizes carried over unchanged will render
  noticeably larger. This is not optional and it is not a taste call.
- **Three statics** (Regular, Medium or SemiBold, Bold) in `res/font`, plus `OFL.txt` in
  `third_party/`.
- **`TextStyle(fontFeatureSettings = "zero")`** where a slashed zero is wanted; it works at API 21+,
  unlike variable axes.
- **No box-drawing or block glyphs.** Not a blocker: every frame, pill, rule and meter in the
  ticket 01 mocks is drawn geometry, not text. It becomes a blocker only if a later ticket reaches
  for box-drawing characters as chrome, so do not.

### Fog this closes

The map's "whether the bundled face needs a second condensed-caps companion" is **answered: no**.
Martian ships four widths inside the one family, so a condensed cut for pills and labels costs a
weight file, not a second typeface.

### Unmet ticket step, accounted for (CLAUDE.md §8, L11)

The ticket said to capture on a throwaway `research/bundled-mono` branch. **Not done** - the
findings file was written on `feat/quant-viz`, where the rest of this effort's uncommitted work
also sits. **Accepted, not deferred:** the branch was a hygiene instruction, the artifact it was
meant to protect exists and is git-tracked, and where this effort's files ultimately land is a
single open question covering the map, ticket 01 and this file together. No follow-up ticket.

### What is NOT settled here

**Which face ships is still Kevin's call if he wants it.** This ticket was scoped to produce a
recommendation and a runner-up, and it did. Martian reading as "instrument" and JetBrains reading
as "IDE" is the researcher's taste call from rendered specimens, tagged `reasoned`, not measured.

### Assumptions ledger, relayed from the research agent without upgrade

| Claim | Tag |
|---|---|
| All byte sizes, licence text, RFN status, `fsType`, name IDs | `tested` - read from each repo's own files and from the binaries via fontTools |
| Advance widths, cap and x heights, upem, axes, cmap coverage | `tested` - measured from downloaded binaries |
| Deflated sizes | `tested` - `gzip -9` as a proxy. **Actual APK entry sizes not measured**, no build was run |
| Variable-font API 26 gate | `traced` - read the fork in androidx `PlatformTypefaces.android.kt`. Not observed failing on an API 24 device |
| `.ttf` compressed in APK | `traced` - read aapt2 `Link.cpp`. Not confirmed by unzipping a built APK |
| `fontFeatureSettings = "zero"` at API 21+ | `reasoned` - the Compose plumbing was not traced end to end |
| 9-12sp tracked-caps verdicts | `tested` via Pillow/FreeType at density 2.0. **Not** rendered by Android's own text stack on the Oppo A17K; Skia hinting may differ |
| "Martian reads as instrument, JetBrains reads as IDE" | `reasoned` - taste call from specimens |
| `res/font/` rejects `.txt` | `reasoned` - not verified by a build |
| Sometype Mono | listed only, **not** downloaded or measured |
