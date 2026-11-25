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
     * Something is wrong now and waiting would cost the driver: a fresh trouble code, an overheat,
     * rough weather on the road. The phone files all three under its `SAFETY` category
     * (`new_trouble_code`, `coolant_overheat`, `rough_weather_driving`), and so does this.
     *
     * Rough weather used to be [ROUTINE] here, which `headunit/CLAUDE.md` sec 4.3 described by
     * grouping it with the lines a trivia game holds off. Kevin's standing rule is that Moose
     * behaves like Alfred and only his personality differs, so it is URGENT now: it survives a
     * goodbye and a running game does not hold it ([heldByGame]). The phone has no game to compare
     * against, and the nearest thing it does have agrees - an active alarm blocks every category
     * except SAFETY (`ProactiveBus`).
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
     * milestone, an arrival reminder, open recalls, the two-hour break nudge, a trivia game's
     * final score, an incoming-call heads-up.
     */
    ROUTINE;

    /** Whether a goodbye keeps this line back instead of dropping it (ruled 2026-11-24). */
    val survivesGoodbye: Boolean get() = this != ROUTINE

    /**
     * Whether a running trivia game holds this line back (`headunit/CLAUDE.md` sec 4.3: the game
     * counts as busy for routine proactives, urgent alerts still fire). A driver asking for
     * something mid-game never reaches a gate at all, so [REQUESTED] is not held either.
     */
    val heldByGame: Boolean get() = this == ROUTINE
}
