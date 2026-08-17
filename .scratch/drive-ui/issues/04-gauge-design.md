# What the gauges actually are

Type: prototype
Status: resolved (2026-08-16, verified built)
Blocked by: -

## Question

The screen today is one 270-degree arc dial (RPM, or speed if RPM was never recorded) plus two pods
(coolant, and the dial's complement), under a hard "glance ceiling of THREE readouts"
(`DrivingModeScreen.kt:61-63`). Kevin: "the gauges can be improved too."

This is a prototype ticket: draft two or three concrete alternatives, render them, and let Kevin
react to pictures rather than descriptions.

1. **Is the arc dial right at all?** It occupies 260dp of a 832dp screen for one number. Alternatives
   worth drawing: a horizontal bar tach, a numeric-primary treatment with a thin motion strip, a
   split dial. Draw them; do not argue them in prose.
2. **Does the glance ceiling of three survive?** It was written for a head unit glanced at while
   driving. A phone in a mount is the same constraint, so the ceiling may well be right - but it
   should be re-affirmed deliberately rather than inherited.
3. **Redline and scale.** `REDLINE_START_FRACTION = 0.85f` and `RPM_SCALE_MAX = 8000f` are constants;
   a 1998 4.0L XJ redlines nowhere near 8000. A scale that never fills is a scale that wastes its
   range. What is the right ceiling, and is it per-vehicle?
4. **Does the dial primitive graduate?** Settled decision 3: `DrivingDial` is private and there is no
   arc primitive in `ui/common/`. If another surface would use it, it moves; if not, it stays.
5. **Stale treatment.** Today staleness demotes colour to `sem.faint` and prints worded age. That is
   good and must survive - but with a live fast lane, "stale" becomes rare and the treatment could be
   stronger without being noisy.

Assets land in this effort's `research/` or as a prototype branch, linked from the Answer.

## Answer (direction settled; prototype pending)

**Kevin gave the visual direction 2026-08-16** with a reference photo, saved as
[research/04-reference-dashboard.jpeg](../research/04-reference-dashboard.jpeg):
**"look at the bars and shit. retro futuristic vibes. think akira, think evangelion"**

The reference is an 80s car dashboard: analog round gauges, and centrally a **segmented graphic-EQ
display** - discrete teal blocks with orange caps, on a printed grid with tick labels - plus
slider/fader controls with orange fills (`COLD - HOT`, `ECONOMY`), chunky labelled buttons, all
amber-on-near-black.

### The design language, extracted

| Reference element | What it becomes here |
|---|---|
| Segmented EQ bars, discrete blocks | **RPM and speed as segment columns**, not swept needles |
| Orange caps at the top of the range | The **redline zone**, drawn as scale annotation, not as a state colour |
| Printed grid + tick labels behind the display | **Scale ticks drawn behind the readout**, not as a separate axis strip |
| Fader with orange fill (`COLD - HOT`) | **Coolant as a fader**, which is literally what that control is in the photo |
| Screen-printed micro-caps labels | Already the deck idiom (`LegionType.stamp`) |
| Amber-dominant, warm, high contrast | **Warmer and more orange-dominant** than the current mint-heavy palette |
| Dense, everything labelled | **Not minimal.** The current screen is three numbers floating in space, which is why a third of it is empty |

### Why this also settles the honesty problem

**A segmented bar is discrete by construction.** A swept needle implies a value between two
readings; a column of blocks cannot. So the ~2 Hz bus ceiling
([ticket 01](01-bus-reality-research.md)) stops being something the design has to apologise for and
becomes something it expresses natively. This is why
[motion policy](06-motion-policy.md)'s interpolation question largely dissolved.

### Answers to this ticket's own questions

- **Q8 - the 270-degree arc dial does not survive as the primary form.** It spends 260dp of an
  832dp screen on one number. Segment columns carry the same reading in far less height and suit
  the cadence better.
- **Q9 - the glance ceiling of three PRIMARY readouts holds**, but it does not govern the dead
  third; secondary/trip content is not glance content.
- **Q10 - `RPM_SCALE_MAX = 8000f` is wrong for this car.** A 4.0L XJ redlines around 4600-5000, so
  today the reading lives in the bottom half and never approaches the redline zone. **Per-vehicle
  maximum, defaulting to ~5500 for the Jeep.** A scale that cannot fill is decoration.
- **Q11 - `DrivingDial` does NOT graduate to `ui/common/`.** No second caller exists, and the arc
  is being replaced anyway.
- **Q12 - SPEED owns the primary readout, not RPM.** Today RPM wins whenever it was ever recorded.
  Driving, not tuning.
- **Q5 (stale treatment) survives unchanged.** Worded age plus demoted colour, on every reading.

**Still open: the prototype itself.** Two or three concrete renderings, installed and screenshotted,
for Kevin to react to. This ticket resolves when he picks one.

## VERIFIED BUILT 2026-08-16 - closed

Swept against the tree. **Every one of this ticket's own answers shipped** in commit `1ff4807`,
`ui/DrivingModeScreen.kt`. All `traced`.

| Requirement | Where |
|---|---|
| Segment columns replace the swept arc | `SegmentColumn` `:594`, `drawSegmentColumn` `:717-742`. **`DrivingDial` no longer exists anywhere** |
| Scale ticks on a grid behind the readout | `drawBehind` on the bar box, `:647-654`, spanning gutter + canvas |
| Coolant as a COLD-HOT fader | `CoolantFader` `:753`, endpoint labels `:786-787` |
| Redline as scale annotation, never state colour | `redlineStartIndex` `:603`, `isRedlineSegment` `:731`, printed label `:690-699`; speed pins it out of range `:382` |
| Dense and labelled | PID code beside every label `:624`, `:771`; `TechnicalFooterBand` `:433` |
| Q10 RPM ceiling 5500 | `:148` |
| Q12 SPEED primary | `:370-388` - first, `displayLarge`, weight 1.3f, 20 segments against RPM's 14 |
| Q11 dial does not graduate to `ui/common/` | it was deleted rather than moved |
| Q5 stale treatment survives | stale flags `:305`/`:313`/`:320`, `sem.faint` demotion `:613`, worded age `:705`/`:790` |

**Two honest caveats on the close:**
1. **The literal deliverable was "two or three renderings for Kevin to pick from"; ONE was built and
   iterated on device instead.** The close condition ("resolves when he picks one") was met by a
   different route - four install-and-look rounds with Kevin steering each. Recording the deviation
   rather than pretending the ticket was followed.
2. **Per-vehicle scales are stated, not built.** `RPM_SCALE_MAX`/`SPEED_SCALE_MPH_MAX` are
   `private const val` (`:148`), and `:130-147` records why: `Vehicle` has no redline or top-speed
   column and adding one is a Room migration that was out of scope. **Still true, still owed.**
