# Research: bundled mono for mission-control

Ticket: `.scratch/mission-control/issues/02-bundled-mono.md`
Resolved: 2026-08-14
Method: every size, advance width, glyph-coverage and license figure below was **measured or read
directly** from the foundry's own repo (GitHub contents API for byte sizes, `fontTools` against the
downloaded binary for metrics, raw `OFL.txt` / `LICENSE.txt` for licensing). Fonts were downloaded
to a scratch directory outside the repo; **nothing was added to `app/src/main/res/font`**.

---

## Recommendation

**Martian Mono Condensed** (Evil Martians, OFL 1.1) as the app-wide face, three statics:
`MartianMonoCondensed-Regular / -Medium / -Bold`.

**Runner-up: JetBrains Mono** (OFL 1.1).

Reasoning is in [Recommendation, argued](#recommendation-argued) at the bottom. The short form:

| | Martian Cond | JetBrains |
|---|---|---|
| Register | industrial/technical by design | coding face, neutral |
| Cap height | **0.800 em** (tallest tested) | 0.730 em |
| Advance | 0.600 em (identical) | 0.600 em |
| 3-weight APK cost | **~124 KB** | ~388 KB |
| Condensed/width family | **4 widths, same family** | none |
| Glyph coverage | 511 cmap, no box-drawing | **1372 cmap, full box-drawing + blocks** |

Martian buys the register, the caps and 3x the byte budget back; JetBrains buys glyph coverage the
chrome probably does not need, because the bezel and tick rails are Compose `Canvas` work
(map charting decision 3), not typed box characters.

---

## 1. Candidates

Ticket's six, plus four added that fit the register. All are OFL 1.1. Anything not on this list was
excluded for licence (not OFL/Apache) or for having no usable weight range.

| Family | Upstream primary source | Why considered / dropped |
|---|---|---|
| JetBrains Mono | `github.com/JetBrains/JetBrainsMono` | finalist |
| Martian Mono | `github.com/evilmartians/mono` | **finalist** |
| IBM Plex Mono | `github.com/IBM/plex` `packages/plex-mono` | finalist, third |
| Azeret Mono | `github.com/displaay/Azeret` | strong, but plain zero and no width axis |
| Space Mono | `google/fonts/ofl/spacemono` | right era, **no Medium/SemiBold exists** |
| Roboto Mono | `google/fonts/ofl/robotomono` | no zero differentiation, no character |
| Red Hat Mono | `google/fonts/ofl/redhatmono` | cheapest (68 KB variable), but lightest strokes |
| Chivo Mono | `google/fonts/ofl/chivomono` | competent, plain zero, no register argument |
| Share Tech Mono | `google/fonts/ofl/sharetechmono` | perfect register, **single weight only** |
| Sometype Mono | `google/fonts/ofl/sometypemono` | listed for completeness, not tested |

---

## 2. Licence

**Every candidate is SIL OFL 1.1. None is Apache 2.0.** Roboto Mono was relicensed: it now lives
under `google/fonts/ofl/` and its `METADATA.pb` reads `license: "OFL"`.

Copyright lines, read from each repo's own `OFL.txt` / `LICENSE.txt`:

| Family | Copyright line (verbatim first line) | Reserved Font Name |
|---|---|---|
| JetBrains Mono | `Copyright 2020 The JetBrains Mono Project Authors (...)` | **none declared** |
| Martian Mono | `Copyright 2021 The Martian Mono Project Authors (...)` | **none declared** |
| IBM Plex Mono | `Copyright © 2017 IBM Corp. with Reserved Font Name "Plex"` | **"Plex"** |
| Azeret Mono | `Copyright 2021 The Azeret Project Authors (...)` | none declared |
| Space Mono | `Copyright 2016 The Space Mono Project Authors (...)` | none declared |
| Roboto Mono | `Copyright 2015 The Roboto Mono Project Authors (...)` | none declared |
| Red Hat Mono | `Copyright 2024 The Red Hat Project Authors (...)` | none declared |

**What OFL 1.1 actually requires** (quoted from `PERMISSION & CONDITIONS` in the shipped licence):

- Clause 2: *"Original or Modified Versions of the Font Software may be bundled, redistributed
  and/or sold with any software, provided that each copy contains the above copyright notice and
  this license. These can be included either as stand-alone text files, human-readable headers or
  in the appropriate machine-readable metadata fields within text or binary files as long as those
  fields can be easily viewed by the user."*
- Clause 1: the font may not be sold **by itself**. Irrelevant: LEGION has no commercial model.
- Clause 3: a **Modified Version** may not keep a Reserved Font Name. Only IBM Plex declares one.
- Clause 5: the font stays OFL. It does **not** infect LEGION's own licence, and does not apply to
  documents produced with it.

**Does a public GitHub repo need a NOTICE entry?** OFL has no NOTICE mechanism (that is Apache 2.0
§4(d)). Clause 2 is satisfied by shipping the copyright + licence text with each copy. Checked in
the binaries: **every candidate embeds `name` ID 13 (licence description) and ID 14 (licence URL),
and all have `fsType = 0` (installable embedding)**, so the `.ttf` in `res/font` technically
carries its own licence in a machine-readable field. That is thin. Do both:

1. Commit the upstream `OFL.txt` verbatim to the repo, e.g. `third_party/martian-mono/OFL.txt`.
   **Not** into `res/font/` - that directory only accepts font and font-family XML resources.
2. Surface the attribution in-app wherever the licences screen ends up.

**Subsetting caveat, and it bites the runner-up harder.** A subset font is a Modified Version.
For JetBrains Mono / Martian Mono / Azeret that is fine (no RFN). For **IBM Plex Mono a subset must
be renamed** - it cannot ship as "IBM Plex Mono". That is another reason it is third here.

---

## 3. Size on disk

All raw byte counts read from the GitHub contents API against the foundry repo. "Deflate" is
`gzip -9` of the same file, used as a proxy for the APK's stored size.

**`.ttf` is compressed inside the APK.** Verified in AOSP: `frameworks/base/tools/aapt2/cmd/Link.cpp`
seeds `extensions_to_not_compress` with image, audio and video extensions only - `.ttf` is not on
that list. So the APK cost is the deflate column, not the raw column.

Set = **Regular + Medium + Bold**, which is what `Type.kt` uses (`Normal`, `Medium`, `SemiBold`,
`Bold`; SemiBold can map to Bold or to a fourth file).

| Family | 3 statics raw | **3 statics deflated** | variable raw | variable deflated | axes |
|---|---|---|---|---|---|
| Martian Mono Condensed | 269,984 | **123,845** | 148,460 (all widths+weights) | 77,654 | wght 100-800, wdth 75-112.5 |
| Martian Mono Standard | 264,460 | **123,545** | same file | same file | same |
| Azeret Mono | 227,912 | **116,841** | 106,092 | 57,610 | wght 100-900 |
| IBM Plex Mono | 410,068 | **172,044** | n/a here | n/a | (separate `plex-mono-variable` pkg) |
| JetBrains Mono | 814,636 | **387,924** | 187,208 | 90,499 | wght 100-800 |
| Space Mono (R+B only) | 197,588 | **92,822** | none published | - | - |
| Roboto Mono | no statics in `google/fonts` | - | 183,700 | 129,374 | wght 100-700 |
| Red Hat Mono | no statics in `google/fonts` | - | 68,356 | 37,383 | wght 300-700 |
| Chivo Mono | no statics in `google/fonts` | - | 123,880 | 63,942 | wght 100-900 |
| Share Tech Mono (1 wt) | 43,272 | 21,525 | none | - | - |

JetBrains Mono is expensive because it is big: **1,372 mapped codepoints / 1,754 glyphs** including
Cyrillic and Greek. Martian is 511 / 567. Glyph count table:

| Family | glyphs | cmap entries |
|---|---|---|
| JetBrains Mono (upstream static) | 1754 | 1372 |
| IBM Plex Mono | 1033 | 930 |
| Roboto Mono | 1006 | 876 |
| Chivo Mono | 873 | 642 |
| Space Mono | 765 | 624 |
| Azeret Mono | 646 | 433 |
| Martian Mono (all cuts) | 567 | 511 |
| Red Hat Mono | 441 | 404 |
| Share Tech Mono | 268 | 267 |

### Is one variable font smaller than three statics?

Numerically yes, always. **But it does not work on LEGION's `minSdk`.**

`app/build.gradle.kts` sets `minSdk = 24`. Traced through the Compose source:
`compose/ui/ui-text/src/androidMain/.../PlatformTypefaces.android.kt` defines

```kotlin
internal fun Typeface?.setFontVariationSettings(
    variationSettings: FontVariation.Settings,
    context: Context,
): Typeface? {
    return if (Build.VERSION.SDK_INT >= 26) {
        TypefaceCompatApi26.setFontVariationSettings(this, variationSettings, context)
    } else {
        this
    }
}
```

and `AndroidPreloadedFont.android.kt` does the same `SDK_INT >= 26` fork, falling back to plain
`Typeface.createFromAsset`. `FontVariation.Settings.toAndroidArray` is `@RequiresApi(O)`.

**Consequence on API 24-25:** the axes are silently ignored. A single variable file resolves to its
default instance for every weight, and Compose then fakes the bold. Silent, not a crash, and
therefore exactly the class of bug L11 exists to catch.

**Ruling: ship three statics.** The variable file is only the right answer if the build ticket also
raises `minSdk` to 26, which is a separate decision and out of this ticket.

---

## 4. Tabular figures

Compose has no `tabular-nums`, so the family is the mechanism. Measured advance widths directly
from `hmtx`:

| Family | digit advance | distinct digit widths | `$ - . , : / > _ % + * # ( ) [ ] < \| ~` share it? |
|---|---|---|---|
| Martian Mono Condensed | 600/1000 = 0.600 em | 1 | **yes, all** |
| Martian Mono Standard | 750/1000 = 0.750 em | 1 | yes, all |
| JetBrains Mono | 600/1000 = 0.600 em | 1 | yes, all |
| IBM Plex Mono | 600/1000 = 0.600 em | 1 | yes, all |
| Azeret Mono | 650/1000 = 0.650 em | 1 | yes, all |
| Space Mono | 612/1000 = 0.612 em | 1 | yes, all |
| Roboto Mono | 1229/2048 = 0.600 em | 1 | yes, all |
| Red Hat Mono | 600/1000 = 0.600 em | 1 | yes, all |
| Share Tech Mono | 540/1000 = 0.540 em | 1 | yes, all |

**All ten digits are one fixed advance in every candidate, and so are `$`, ASCII `-`, U+2212 minus,
`.` and `,`.** Money columns line up. Confirmed per-glyph, not inferred from the `isFixedPitch` flag
(which is unreliable - **Azeret Mono reports `post.isFixedPitch = 0` and `panose.bProportion = 0`
while every advance is in fact 650**; Roboto Mono reports `isFixedPitch = 0` with `panose = 9`).

**One real trap found: Azeret Mono's em dash `—` is 1300 units, exactly double width.** Any string
mixing an em dash into a mono column breaks the grid in Azeret. Every other candidate keeps `—` on
the grid. CLAUDE.md's copy uses em dashes nowhere, so this is a latent hazard rather than an active
one, but it is one more reason Azeret is not the pick.

### Zero / letter-O separation

An instrument face must not confuse `0` and `O`. Read from glyph contours and the GSUB feature list:

| Family | default zero | `zero` GSUB feature | alternates |
|---|---|---|---|
| JetBrains Mono | **marked** (dotted) | yes | `zero.zero` |
| Martian Mono | **marked** (slashed) | no feature needed | - |
| IBM Plex Mono | **marked** | yes | `zero.alt01`, `zero.alt02` |
| Red Hat Mono | **marked** (slashed) | yes | `zero.slash` |
| Space Mono | **marked** | no `zero` feature | - |
| Azeret Mono | **plain** (2 contours) | **yes** - opt in | - |
| Chivo Mono | plain | yes - opt in | - |
| Roboto Mono | plain, visually identical family shape to `O` | **no** | none |

Where a `zero` feature exists, Compose can switch it on globally with
`TextStyle(fontFeatureSettings = "zero")` - that path is `Paint.setFontFeatureSettings`, API 21+,
so it works at `minSdk 24` unlike variable axes. **Roboto Mono has no such escape hatch**
(its entire GSUB is `smcp`), which alone disqualifies it for a money/PID app.

---

## 5. Glyph coverage

Checked against the exact set the ticket names plus everything LEGION plausibly renders. "missing"
is verbatim, from the font's own cmap.

| Set | JetBrains | Martian | IBM Plex | Azeret | Space | Roboto | Red Hat |
|---|---|---|---|---|---|---|---|
| `$ - . : / > _ % + = * # ( ) [ ] < \| ~ ^ & @ ? ! ; ' " \` \\` | all | all | all | all | all | all | all |
| `– — −` (dashes) | all | all | all | all (`—` off-grid) | all | all | all |
| `° µ ± × ÷` | all | all | all | all | all | all | all |
| `≤ ≥ ∞ Ω` | missing `Ω` | **missing all 4** | all | missing `Ω ∞` | all | all | **missing all 4** |
| `↑ ↓ → ←` (deltas) | **all** | **all** | **all** | **all** | **all** | missing all | missing `→ ←` |
| `↔ ↕ ⇧ ⇩` | missing `⇩` | missing all | missing `⇧ ⇩` | missing all | missing `⇧ ⇩` | missing all | missing all |
| `▲ ▼ ▶ ◀` | **all** | missing all | missing all | missing all | missing all | missing all | missing all |
| box drawing `─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼ ═ ║` | **all** | **none** | **all** | none | none | none | none |
| blocks `█ ░ ▒ ▓` | **all** | none | **all** | none | none | none | none |
| `′ ″ … ✓ ✗ ● ○ ■ □` | **all** | none | missing `✗ ● ○ ■ □` | missing `′ ″ ✓ ✗ ■ □` | missing `✓ ✗ ● ○ ■ □` | missing 6 | missing 6 |
| `¢ £ ¥ €` | all | all | all | all | all | all | all |
| `₩ ₱ ₿` | missing `₩ ₱` | missing all | **all** | missing `₩ ₱` | missing `₩ ₿` | missing all | missing all |

**Reading of this table.** The arrows LEGION actually needs for deltas (`↑ ↓ → ←`) are present in
all five serious candidates. Box drawing and block elements are a **JetBrains/Plex-only** capability.
That matters only if the chrome types its rails as characters. Map charting decision 3 says the
bezel, corner arcs and registration ticks are drawn once in the shell, and decision 2 puts frames
and pills in red-orange outline - i.e. Compose `Canvas`/`Border`, which is the right call anyway
(a Canvas rail hits any thickness and colour; a `─` is stuck at the font's stroke weight). So the
Martian gap here is a real but low-value loss.

`₩ ₱ ₿` gaps are irrelevant: ledger is SGD/USD.

---

## 6. Caps at 9-12sp with ~0.2em tracking

This is the load-bearing test and it was done by **rendering**, not by reasoning: `QUARANTINE LOG`
and `PROVISIONAL` set at 9sp and 11sp with 0.2em tracking, mint on near-black, at density 2.0
(Oppo A17K's approximate density), then magnified for inspection. Objective metrics alongside:

| Family | cap/em | x-height/em | x/cap | advance/em | 9sp tracked caps verdict |
|---|---|---|---|---|---|
| **Martian Mono Cond** | **0.800** | 0.600 | 0.750 | 0.600 | **best. Biggest, sturdiest caps at the same column width as anything else.** |
| JetBrains Mono | 0.730 | 0.550 | 0.753 | 0.600 | very good. Clearly legible, slightly lighter than Martian |
| Azeret Mono | 0.698 | 0.544 | 0.779 | 0.650 | good, but 8% wider per character for smaller caps |
| IBM Plex Mono | 0.698 | 0.516 | 0.739 | 0.600 | fine, noticeably lighter stroke - reads quieter, less "instrument" |
| Roboto Mono | 0.711 | 0.528 | 0.743 | 0.600 | fine and characterless |
| Space Mono | 0.700 | 0.496 | 0.709 | 0.612 | thins out at 9sp; strokes get fragile |
| Share Tech Mono | 0.700 | 0.500 | 0.714 | 0.540 | **mushy at 9sp.** Thinnest strokes tested |
| Red Hat Mono | 0.700 | **0.488** | 0.697 | 0.600 | **mushiest.** Lowest x-height, lightest weight |

Nothing here dissolves at 9sp except Red Hat Mono and Share Tech Mono. Martian Condensed is the
only one that gets *bigger* without getting *wider*, which is exactly the trade a tracked panel
label wants.

**Consequence for the build ticket:** Martian's 0.800 cap height renders ~10% larger than
`Type.kt`'s current sizes assume (they were tuned against `FontFamily.Monospace`). The whole scale
needs a pass down. That is in the build ticket's scope per this ticket's own scope note, but it is
not optional - dropping in the font without retuning will make every header oversized.

Martian's default line box is also tight: `typoAscender 1000 / typoDescender -200` = 1.20 em, vs
JetBrains' 1.32 em. `Type.kt`'s explicit `lineHeight` on every role already neutralises this.

---

## 7. Condensed companion

The map's fog asks whether a second face is needed for pills and labels. **With Martian Mono the
answer is no - the width range is inside the family.** Measured from the upstream statics:

| Cut | digit advance | cap height | Regular file size |
|---|---|---|---|
| MartianMonoCondensed | **0.600 em** | 0.800 | 85,776 |
| MartianMonoSemiCondensed | 0.650 em | 0.800 | 85,300 |
| MartianMono (standard) | 0.750 em | 0.800 | 84,632 |
| MartianMonoSemiExpanded | ~0.80 em | 0.800 | 84,920 |

Eight weights (Thin - ExtraBold) exist in each of the four widths, all as statics, plus one
`MartianMono[wdth,wght].ttf` covering the lot at 148,460 bytes. Cap height is constant across
widths, so mixing Condensed body rows with Standard hero readouts keeps a single optical baseline.

No other candidate offers a mono condensed cut:

| Family | condensed/narrow cut |
|---|---|
| Martian Mono | **yes, 4 widths + `wdth` axis** |
| JetBrains Mono | no |
| IBM Plex Mono | **no** - `plex-sans-condensed` exists, there is no `plex-mono-condensed` package |
| Azeret Mono | no (`wght` only) |
| Space Mono | no |
| Roboto Mono | no (Roboto Condensed is the sans) |
| Red Hat Mono | no |

---

## Recommendation, argued

### Pick: Martian Mono Condensed

1. **Register.** It is the only finalist designed as a technical/industrial face rather than a
   coding face. Rendered against real ledger rows it reads as instrument output; JetBrains and Plex
   read as an IDE.
2. **Caps win the ticket's hardest test.** 0.800 cap height at a 0.600 em advance is the best
   size-per-column-width of anything tested. Panel labels at 9-12sp tracked are where the chrome
   either works or does not, and this is the face that holds there.
3. **Cheapest serious option.** ~124 KB of APK for three weights, a third of JetBrains.
4. **It answers ticket point 7 by itself.** Four widths, one family, one licence, constant cap
   height. The fog item about a second condensed companion closes without a second face.
5. **Licence is clean.** OFL 1.1, no Reserved Font Name, so a later Latin-only subset stays legal
   under the same name.
6. **Zero is slashed by default**, no feature-flag plumbing needed at `minSdk 24`.

**What you give up, stated plainly:** box-drawing and block characters, `≤ ≥ ∞ Ω`, and `▲ ▼`. The
first is covered by Canvas per map decision 3; the second is chart-axis material that would be
better as text anyway (`<=`, `MAX`); the third needs a vector icon. If a later ticket decides the
chrome will be typed out of box characters, this recommendation flips to JetBrains.

**Also stated plainly:** Martian's letterforms are mannered. Long prose in it will be more tiring
than JetBrains. LEGION's longest strings are merchant names and short status lines, so this is a
low-exposure risk - but it is a taste call and Kevin should see it on-device before the build
tickets commit to it.

### Runner-up: JetBrains Mono

The safe pick. Largest glyph repertoire by a wide margin (box drawing, blocks, `▲▼`, check/cross
marks), dotted zero, excellent 9sp caps, OFL with no RFN. It loses on three counts: **3x the APK
cost** (388 KB deflated for three weights, driven by Cyrillic + Greek it will never render), **no
condensed cut**, and a register that is competent-neutral rather than mission-control. If the byte
budget were irrelevant and the chrome were character-drawn, it would be the pick.

If JetBrains is chosen, subset it - no RFN means the subset may keep the name - and the 388 KB
drops sharply.

### Third: IBM Plex Mono

Full box-drawing, best currency coverage, sane 172 KB, and the most conservative letterforms of the
three. Two marks against: the **Reserved Font Name "Plex"** blocks a subset from keeping its name,
and its lighter stroke and 0.516 x-height make it the quietest of the finalists - it does not carry
the ref photos' instrument tone.

### Explicitly not recommended

| Family | Disqualifier |
|---|---|
| Roboto Mono | no zero/O differentiation and no `zero` feature to fix it |
| Space Mono | **no Medium or SemiBold exists**; `Type.kt` needs both, so they would be synthesised |
| Share Tech Mono | single weight; mushy at 9sp |
| Red Hat Mono | lowest x-height, lightest strokes, worst of the set at 9sp tracked |
| Azeret Mono | plain zero (fixable) but the **double-width em dash** is a live grid hazard |

---

## Implementation notes for the build ticket

1. Three files into `app/src/main/res/font/`, lowercase-underscore names only:
   `martian_mono_condensed_regular.ttf`, `_medium.ttf`, `_bold.ttf`.
2. `Type.kt`'s `private val Mono = FontFamily.Monospace` becomes a `FontFamily(Font(...), ...)`
   with `FontWeight.Normal / Medium / Bold`. `FontWeight.SemiBold` in `titleMedium` must be
   remapped or a fourth static added, or Compose will synthesise it.
3. **Retune the scale down ~10%** for the 0.800 cap height. Non-optional.
4. `third_party/martian-mono/OFL.txt` committed verbatim, plus attribution on the licences surface.
   Do not put the `.txt` inside `res/font/`.
5. Do **not** ship the variable font while `minSdk = 24`. See section 3.
6. Verify on the Oppo A17K, per the map's "closes when installed and verified on the phone".

## Unmet ticket step

The ticket says *"Capture on a throwaway `research/bundled-mono` branch."* This file was written on
the current branch; **no branch was created**. Deliberate - the brief for this pass was research
only. Orchestrator to accept or to move the file onto that branch before resolving the ticket.

## Sources

All primary. Byte sizes via `api.github.com/repos/.../contents/...`; metrics via `fontTools` against
the downloaded binaries; licences via `raw.githubusercontent.com`.

- https://github.com/JetBrains/JetBrainsMono - `OFL.txt`, `fonts/ttf/`, `fonts/variable/`
- https://github.com/evilmartians/mono - `OFL.txt`, `fonts/ttf/`, `fonts/variable/`
- https://github.com/IBM/plex - `packages/plex-mono/LICENSE.txt`, `packages/plex-mono/fonts/`
- https://github.com/displaay/Azeret - `OFL.txt`, `fonts/ttf/`, `fonts/variable/`
- https://github.com/google/fonts - `ofl/spacemono`, `ofl/robotomono`, `ofl/ibmplexmono`,
  `ofl/martianmono`, `ofl/azeretmono`, `ofl/jetbrainsmono`, `ofl/redhatmono`, `ofl/chivomono`,
  `ofl/sharetechmono`, `ofl/sometypemono`
- https://github.com/androidx/androidx -
  `compose/ui/ui-text/src/androidMain/kotlin/androidx/compose/ui/text/font/PlatformTypefaces.android.kt`,
  `AndroidPreloadedFont.android.kt`, `AndroidFontLoader.android.kt`,
  `PlatformFontVariationSettings.android.kt`
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/cmd/Link.cpp
