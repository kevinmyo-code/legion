package com.kevin.legion.vehicle

/**
 * Whether the app is allowed to SHOW an mpg figure anywhere - voice, glance cards, drilldown
 * charts, sparklines, generated narrative prose. **Display-only.** [TelemetryRecorder] keeps
 * computing and storing `MPG_TRIP` exactly as before, and [DailyDriveLogController],
 * [MonthlyRecapController], and [YearlyWrappedController] keep rolling it into `avgMpg` on their
 * own tables - none of that changes, so a correction factor can be applied retroactively once a
 * fill-up exists. [SHOW_MPG] is the ONE flag every mpg-rendering surface checks; flipping it back
 * to `true` is the entire re-enable, nothing else should need to change.
 *
 * **Why (ticket 09, `.scratch/drive-ui/issues/09-mpg-scale-bug.md`).** Kevin's 1998 Jeep Cherokee
 * (4.0L I6) has one finalised drive on record: 29.4 mpg, where that engine genuinely does 14-15
 * mpg in city driving - about **1.9x too high**. Investigation against a copy of the real database
 * ruled out the obvious suspects:
 *  - The integration formula itself is faithful - hand re-integrating the raw samples for that
 *    drive reproduces 29.0 mpg against the 29.36 actually recorded.
 *  - MAF coverage inside that drive was 100% - this is not a sparse-sample artifact.
 *  - The MAF **values** are the suspect input: median 4.15 g/s, where a 4.0L I6 cruising should
 *    read roughly 15-25 g/s.
 *
 * The 4.0L is **speed-density** - it has no real mass-airflow sensor - so PID `0110` is a
 * PCM-**synthesised** proxy, not a measured quantity, and there is no reason a synthesised proxy
 * should be scaled the way [TelemetryRecorder]'s gallons-integration formula assumes a genuine MAF
 * sensor's output is. **The correction factor cannot be proven without a tank-to-tank fill-up** -
 * the only ground truth available - which needs Kevin to supply. Guessing a fudge factor that
 * happens to make one drive look plausible is exactly the wrong fix (the ticket's own framing).
 *
 * **Kevin's ruling (2026-08-16): suppress, not merely label unverified.** CLAUDE.md §4 rule 5
 * would ordinarily allow shipping an unproven figure labelled as an estimate - but a number known
 * wrong by roughly 2x is not an estimate worth a driver's trust, it is noise, and mislabelling it
 * "unverified" would still let a wrong figure anchor a decision. Every surface that would otherwise
 * show mpg must say so in words wherever an absence would otherwise read as a bug (a chart that
 * just vanishes with no explanation is its own confusion) and the voice tool must never answer
 * with a number.
 *
 * Do not "fix" this by deleting the flag - the next reader should flip [SHOW_MPG] to `true` only
 * once a real fill-up has produced a calibration factor and it has been applied.
 */
object MpgTrust {
    /**
     * Single kill switch. `false` = every mpg-rendering surface withholds the figure. Flip to
     * `true` once a tank-to-tank fill-up calibrates [TelemetryRecorder]'s MAF-integration formula
     * (or a correction factor derived from one is applied) - see this object's own doc for why.
     */
    const val SHOW_MPG: Boolean = false

    /**
     * Short, stamp-style line for a surface that would otherwise render a chart, row, or section
     * where mpg used to appear - so a driver reads a stated reason rather than a silent gap.
     */
    const val WITHHELD_STAMP: String = "mpg withheld pending fill-up calibration"

    /** Even shorter form for a plain label/value cell (e.g. a [com.kevin.legion.ui.common.DeckRow] value). */
    const val WITHHELD_ROW_VALUE: String = "withheld"

    /**
     * The voice tool's refusal - spoken in full sentences, not stamp style, since it is read aloud
     * by the Live model rather than rendered as UI text. States the reason briefly; never invites
     * a follow-up number.
     */
    const val VOICE_REFUSAL: String =
        "I'm not reporting mpg on this car right now - the on-board estimate reads almost twice " +
            "what this engine actually gets, and it needs a real fill-up to calibrate before I'll trust it."
}
