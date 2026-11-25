package com.kevin.midnightai.service

/**
 * What kind of line the proactive engine is asking for: the one thing the holding area and the
 * goodbye rule need to know about it.
 *
 * Kevin ruled on 2026-11-24 that a goodbye drops a held ROUTINE line. The bus carried a prompt and
 * nothing else, so there was no way to obey the word "routine": a fresh trouble code raised while
 * Moose was mid-sentence was held exactly like idle chatter and went with it on the sign-off, and
 * so did an answer the driver had asked for and that arrived late. This is the vocabulary that
 * tells them apart.
 *
 * Ported from the phone, narrowed to what the unit has. Alfred's `ProactiveCategory` equivalent
 * has nine values because each one is a switch on a settings screen; the unit has one mute
 * preference, so what is left is the distinction those categories are built on: who asked, and
 * whether waiting costs anything.
 *
 * **Declaration order is the priority order.** The holding area speaks the most urgent line it
 * holds first, and when it is full it evicts the least urgent.
 */
enum class ProactiveKind {
    /**
     * Something is wrong now and waiting would cost the driver: a fresh trouble code, an overheat.
     * The phone files these under its `SAFETY` category.
     *
     * Rough weather is deliberately NOT one of these here, though the phone's SAFETY category
     * includes it: `headunit/CLAUDE.md` sec 4.3 counts the rough-weather line with the routine ones
     * a trivia game holds off ("idle chatter/weather/arrival hold off; urgent alerts still fire"),
     * and the unit's own code has always treated it that way. Left as it is and named as a
     * difference from the phone rather than changed inside a goodbye fix.
     */
    URGENT,

    /**
     * The driver asked for this line and it arrived late: the Logbook CODES "ASK" tap, a late
     * Spotify play report, Setup's "Test voice". The phone never gates one of these at all
     * (`ProactiveBus.speakSolicited`), so a goodbye may not swallow it either - he asked.
     */
    REQUESTED,

    /**
     * Everything unprompted that can wait: the startup opener, idle chatter, an odometer
     * milestone, an arrival reminder, open recalls, rough weather, the two-hour break nudge, a
     * trivia game's final score, an incoming-call heads-up.
     */
    ROUTINE;

    /** Whether a goodbye keeps this line back instead of dropping it (ruled 2026-11-24). */
    val survivesGoodbye: Boolean get() = this != ROUTINE
}
