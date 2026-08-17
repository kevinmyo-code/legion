# Layout for a phone that is 384 x 832, not a head unit

Type: prototype
Status: open - decisions settled, layout pending the prototype
Blocked by: 04, 05

## Question

On the A25, roughly a third of the driving screen is empty - everything sits in the top half, then a
void, then EXIT pinned at the bottom. Verified by screenshot 2026-08-16.

Blocked by [gauge design](04-gauge-design.md) and [trip content](05-trip-content.md) because there is
nothing to lay out until those decide what exists.

1. **The dead third.** Trip content is the obvious tenant, but confirm that rather than assuming -
   deliberate empty space on a glanceable screen is a legitimate choice, and cramming it is the easy
   wrong answer.
2. **384 x 832 dp, measured.** Every layout figure in `.scratch/mission-control/` was measured
   against the retired A17k at 360 x 806 (`MEMORY.md`). Nothing here inherits those numbers without
   re-measuring.
3. **Reach.** A phone in a mount is glanced at and rarely touched; a phone in a hand is touched.
   EXIT is a 72dp key at the very bottom, which is the easiest place to reach one-handed and the
   worst place to avoid hitting by accident. Decide whether EXIT wants a confirm, given it is the
   one control on the screen.
4. **The Alfred strip.** It sits mid-screen today between the pods and the void. Whether the talk
   affordance belongs there, or at thumb level, is a reach question too.
5. **What the shell already does.** `MainActivity` strips its chrome for this route
   (`MainActivity.kt:299`, `:369`, `:398`) while still respecting insets. Any layout works inside
   that, and does not re-solve it.

## Answer (settled, pending the prototype)

**Stark's recommendations, put to Kevin 2026-08-16, unopposed.**

- **Q26 - fill the dead third with trip content.** A third of a glance screen earning nothing is
  waste rather than restraint. Confirmed as the tenant by
  [trip content](05-trip-content.md)'s three figures.
- **Q27 - EXIT keeps no confirm dialog.** It is the only control on the screen; making it annoying
  is worse than an occasional stray exit.
- **Q28 - the Alfred strip moves to thumb level**, just above EXIT, rather than sitting mid-screen
  between the pods and the void.
- **Q29 - portrait only.** Landscape stays in the fog until a mount actually exists.
- **384 x 832 dp is re-measured, not inherited.** Every figure in `.scratch/mission-control/` was
  measured against the retired A17k at 360 x 806.

**Still open:** the concrete layout, which lands with [the gauge prototype](04-gauge-design.md).
Kevin's reference direction is dense-and-labelled rather than minimal, so the layout question is
now "what earns a place on a busy instrument panel", not "how do we fill a void".
