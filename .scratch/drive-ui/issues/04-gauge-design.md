# What the gauges actually are

Type: prototype
Status: open
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
