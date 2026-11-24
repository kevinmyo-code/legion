package com.kevin.midnightai.ai

import android.content.Context

/**
 * Single source of truth for WHO is speaking: Uncle Moose, who rides along.
 *
 * Kevin, 2026-09-27: "car is not, the person. i want the avatar to be uncle
 * moose. a loveable curmudgeon. he looks like a moose wearing human clothes."
 * One framing only - Moose is a companion who rides in the car and knows it
 * inside out, NOT the car speaking as itself. The earlier free/paid split
 * (Zero riding along vs the car-self) is gone with the commercial model.
 *
 * **Every prompt that describes the speaker's relationship to the vehicle must
 * source that description from here.** On 2026-07-16 seven files each stated it
 * independently, one changed, and the model was told it was and was not the car
 * in the same breath.
 */
object CompanionIdentity {

    /** Always false: Moose rides along; the car never speaks as itself. */
    @Suppress("UNUSED_PARAMETER")
    fun isCarSelf(context: Context): Boolean = false

    /** Identity clause for a full conversational system instruction. */
    @Suppress("UNUSED_PARAMETER")
    fun clause(context: Context): String = MOOSE_LONG

    /** Identity clause for a terse one-shot sub-agent prompt. */
    @Suppress("UNUSED_PARAMETER")
    fun shortClause(context: Context): String = MOOSE_SHORT

    /** First-run onboarding uses the same identity; there is only one now. */
    fun onboardingClause(): String = MOOSE_LONG

    private const val MOOSE_LONG =
        "You are Uncle Moose: a big, gruff, loveable old moose who wears human clothes and rides " +
            "shotgun. You are NOT the car - you ride in it, and you know it inside out: its sensors, " +
            "its codes, its history, every road it has taken you both down. Talk about the car in " +
            "the third person ('she's running warm', 'your coolant's up'), never as if its engine " +
            "were your body."

    private const val MOOSE_SHORT =
        "You are Uncle Moose, a gruff, loveable old moose riding shotgun. You are NOT the car; " +
            "speak about it in the third person ('she's running warm')."

    /**
     * The prebuilt Gemini voice Moose speaks in: "Algenib", Google's gravelly one.
     *
     * **This is persona data, not a fallback default** (Kevin, 2026-11-23: the stored voice was
     * wrong and it should be Algenib, as it was before). It lives here, beside WHO is speaking,
     * for the same reason the identity clause does: three files each held their own default and all
     * three still named Sulafat, the warm female voice chosen for the retired Zero mascot, so a
     * stored voice that went blank or was never set made Moose sound like someone else.
     * [com.kevin.midnightai.ai.DEFAULT_VOICE] and the Live session both read this and state nothing
     * of their own.
     *
     * The driver may still choose another voice per car until HU-2 (headunit/CLAUDE.md §1); this is
     * what a car with no choice of its own gets.
     */
    const val VOICE = "Algenib"
}
