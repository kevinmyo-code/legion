# UI Prototype (ADAPTED for LEGION)

Generate **several radically different variants** of a surface, put them side by side, and let Kevin
flip between them, pick one (or steal bits from each), and throw the rest away.

If the question is about logic or state rather than what something looks like - wrong branch. Use
[LOGIC.md](LOGIC.md).

Upstream renders variants on a web route behind a `?variant=` URL param with a floating bottom bar.
There are no routes or URLs here: this is Jetpack Compose on an Android phone. The two shapes below
replace it.

> **Read this before prototyping any surface.** LEGION's `ui/` is a deliberate clean slate. The
> city-pop design language died with the 2026-07-30/31 pivot, and **no replacement has been chosen
> yet**. There is no theme, no token set, and no reference imagery. So a UI prototype here is often
> establishing the vocabulary rather than applying it, which makes "radically different" matter more
> than usual, not less.

## When this is the right shape

- "What should the ledger transaction list look like on a phone?"
- "Show me a few takes on the app shell before I commit to navigation."
- "How should a quarantined statement read?"
- Any time Kevin would otherwise spend a day picking between three vague mockups in his head.

## Two sub-shapes - strongly prefer sub-shape A

A UI prototype is much easier to judge when it's **butting up against the rest of the app**: real
density, real data, real neighbours. A variant floating in a vacuum always looks fine. Default to A.

### Sub-shape A - `@Preview` variants (preferred)

One `Proto`-prefixed file next to the composable being explored, holding the variants as separate
composables, each with its own `@Preview`.

- **Name previews for the idea, not the number**: `@Preview(name = "Ledger: grouped by month, balance pinned")`, not `Variant1`.
- **Preview at phone size.** This is a phone-only app now (CLAUDE.md §2). Use the default preview
  size or an explicit phone width; the Oppo A17K is roughly 360dp wide, which is the narrow case
  worth checking against. **Do not use 1024x600** - that was the head unit, and the head unit no
  longer constrains design.
- **Feed realistic fake data, not `"Lorem"`.** Real-length merchant descriptions, plausible
  statement amounts in `Long` cents, an actual bank name. Ledger rows in particular look fine with
  short fake strings and fall apart with real ones.
- **Wrap in whatever theme exists.** If the design-language decision has landed, use it. If it has
  not, say explicitly in the preview name which baseline you assumed.

Android Studio's Preview pane is the "flip between variants" surface. Kevin sees them all at once
without launching anything, which is the closest thing this project has to upstream's bottom bar.

### Sub-shape B - a debug variant switcher (when it needs to be live)

Reach for this only when the question genuinely can't be answered statically: it depends on live
data, real motion, real touch, or scrolling behavior with hundreds of rows.

- Add a **debug-only** screen or a `Proto` composable behind an existing debug flag
  (`DebugSettings`), never a new production entry point.
- Variants switch from an on-screen row of buttons: a plain `Row` of the variant names, tap to swap.
  This is the direct analogue of upstream's floating bottom bar.
- ADB works on this project's phone, so running it is cheap. Use that.
- **Rip it out before merge.** It is not a feature.

## Rules that apply to both

- **Motion is legal.** The frame-clock-only rule and the `ui/Motion.kt` ban list
  (`AnimatedVisibility`, `tween`, `infiniteTransition`, `animate*AsState`, `Crossfade`) were
  head-unit constraints, lifted by the phone-only pivot. There is no CI grep-check for them in this
  repo. Use normal Compose animation. If you have read an older skill or shelf saying otherwise, it
  is frozen Midnight AI history.
- **No city-pop.** Do not reinstate the night palette, the cassette objects, the aged-paper binder,
  the katakana secondary lines, or generated character art. That language is dead and so is its
  mascot.
- **Radically different means radically different.** Three variants that differ by 8dp of padding
  waste the prototype. Change the actual idea: what's the focal object, what's demoted, what's cut.
- **Data-heavy surfaces are the hard case.** Ledger is tables of money on a narrow screen. Prototype
  the dense case, not the three-row case, or the answer will not survive contact with real data.
- **Estimates must read as estimates.** Pantry macros are LLM guesses (CLAUDE.md §4 rule five). If a
  variant shows them, it must show them as estimates. That is a guardrail, not styling.
- **No image generation.** There is no image-gen path in this app any more; do not add one for a
  layout question.

## Capture

Fold the validated decision into the real code, then commit the prototype to a throwaway branch out
of `dev` and leave a context pointer on the ticket. Record which variant won **and why** - the "why"
is the durable part, and it belongs in the spec or `memory/library/decisions.md`. `dev` keeps only
the validated decision.
