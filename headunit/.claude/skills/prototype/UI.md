# UI Prototype (ADAPTED for MIDNIGHT AI)

Generate **several radically different variants** of a surface, put them side by side, and let Kevin
flip between them, pick one (or steal bits from each), and throw the rest away.

If the question is about logic or state rather than what something looks like — wrong branch. Use
[LOGIC.md](LOGIC.md).

Upstream renders variants on a web route behind a `?variant=` URL param with a floating bottom bar.
There are no routes or URLs here: this is Jetpack Compose on an Android head unit. The two shapes
below replace it.

## When this is the right shape

- "What should the customizable Cruise layout look like?"
- "Show me a few takes on the garage-scene settings screen before I commit."
- "Try a different treatment for the recap cassette."
- Any time Kevin would otherwise spend a day picking between three vague mockups in his head.

## Two sub-shapes — strongly prefer sub-shape A

A UI prototype is much easier to judge when it's **butting up against the rest of the app**: real
palette, real density, real avatar, real telemetry strip. A variant floating in a vacuum always looks
fine. Default to A.

### Sub-shape A — `@Preview` variants (preferred)

One `Proto`-prefixed file next to the composable being explored, holding the variants as separate
composables, each with its own `@Preview`.

- **Name previews for the idea, not the number**: `@Preview(name = "Cruise: widgets locked, avatar left")`, not `Variant1`.
- **Preview at the real device size.** The head unit is a cheap double-DIN, landscape, roughly 1024x600. `@Preview(widthDp = 1024, heightDp = 600)`. A phone-shaped preview answers the wrong question, and D1 (dual-target) means phone and head unit are genuinely different targets — say which one each preview is.
- **Wrap in the real theme** (`AriaTheme`) and feed **realistic fake data**, not `"Lorem"`. Fake OBD values that a 1998 XJ would actually show, a real-length track title, the actual weekday kanji.
- **Dark by default.** Every surface here is a night surface (`0xFF0D1418`).

Android Studio's Preview pane is the "flip between variants" surface. Kevin sees them all at once
without launching anything, which is the closest thing this project has to upstream's bottom bar.

### Sub-shape B — a debug variant switcher (when it needs to be live)

Reach for this only when the question genuinely can't be answered statically: it depends on live
telemetry, real motion, real touch, or how it feels in the dark at 60mph.

- Add a **debug-only** screen or a `Proto` composable behind an existing debug flag (`DebugSettings`), never a new production entry point.
- Variants switch from an on-screen row of buttons: a plain `Row` of the variant names, tap to swap. This is the direct analogue of upstream's floating bottom bar.
- It runs on the emulator or the head unit via the usual Run button.
- **Rip it out before merge.** It is not a feature.

## Rules that apply to both

- **Tokens only.** `AriaColors` / `AriaType` / `AriaDimens`. No ad-hoc hex — §12's coherence law is often the very thing being judged, so a prototype in the wrong palette is worse than none.
- **Frame-clock motion only, and CI enforces it.** `ui/Motion.kt` (`animPhase`/`triangle`/`wave`/`rememberRamp`). `AnimatedVisibility`, `tween(`, `infiniteTransition`, `animate*AsState`, `Crossfade` are banned in `ui/` and the CI grep-check will fail the build. They also silently freeze on real head units (animator scale 0), so they'd lie to you about the result anyway.
- **Re-read the references first.** `C:\Users\kevin\OneDrive\Pictures\UI Style References`, per §12. `3.png` is the Yoko/character direction, `casette reference.png` the widget shells, `Binder style.jpg` the logbook masthead, `Screenshot 2026-07-01 221037.png` the caption bar, `maxresdefault.jpg` the display type.
- **Respect the surface's job.** Driving surfaces are SPARE, parked surfaces are RICH. A gorgeous dense Cruise variant is a wrong answer, not a bold one. The `AmbientWallpaper` backdrop is the one authorized moving exception on Cruise.
- **Radically different means radically different.** Three variants that differ by 8dp of padding waste the prototype. Change the actual idea: what's the focal object, what's demoted, what's cut.
- **No image generation.** Don't burn `AvatarStudio` calls on a layout question; use an existing cached avatar or a flat placeholder shape.

## Capture

Fold the validated decision into the real code, then commit the prototype to a throwaway branch out
of main and leave a context pointer on the ticket. Record which variant won **and why** — the "why"
is the durable part, and it's what belongs in the spec or `decisions.md`. Main keeps only the
validated decision.
