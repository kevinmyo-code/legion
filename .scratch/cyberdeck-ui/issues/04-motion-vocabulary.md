# Motion vocabulary

Type: grilling
Status: resolved
Blocked by: 01

## Question

What moves, when, and what is the theatre ration spent on? Decide the motion system once:
- App/screen entry: boot sequence? scan-in? How long before it gets old (first-run vs every-run)?
- Ambient motion: ticking readouts, pulse lines, cursor blinks - which panels earn it, and what
  it costs in recomposition (consult compose-recomposition-performance).
- Transitions between modules/screens.
- The rationed full-theatre effects (glitch, scanline): which few moments get them - candidates
  from the fog: boot, ingest commit, quarantine event.
- Reduced-motion / battery posture.

Motion ban is lifted (CLAUDE.md §7) - normal Compose animation is allowed. Consult
compose-animations.

## Answer

Grilled with Kevin, 2026-08-07. The system:

1. **Boot: cold start only** (~800ms, tap-to-skip). Warm returns from recents are instant. The
   deck "powers on" a few times a day; glancing at data is never taxed.
2. **One-shot draw-ins**: meters fill and charts draw over ~350ms on screen entry. Never loop.
3. **Ambient motion is exactly ONE element**: the blinking block cursor in the deck's top bar.
   Nothing else animates continuously - battery, and continuous animation stays out of
   recomposition-heavy trees (consult compose-recomposition-performance and
   compose-state-deferred-reads at build time: drive draw-phase reads, not composition).
4. **The theatre ration is spent on exactly three moments**: boot (scan sweep), ingest commit
   (brief sweep over the affected panel), quarantine (red glitch flicker - the one place full
   theatre serves §4, that state SHOULD be arresting). Nothing else gets effects.
5. **Reduced-motion / animator scale 0 respected**: one-shots collapse to instant, cursor stops
   blinking. (Head-unit animator-scale-0 was yesterday's constraint; today it is an
   accessibility path, and it must still render a complete UI.)
