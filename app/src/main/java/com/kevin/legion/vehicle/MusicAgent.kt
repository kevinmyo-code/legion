package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.CompanionIdentity
import com.kevin.legion.ai.SubAgent

/**
 * The music-discovery specialist [SubAgent]. Pre-seeded with the driver's
 * listening taste and what's already saved on the head unit (both always needed
 * to avoid recommending something they already have), it answers in a single
 * search-grounded POST (askTyped with useSearch) - google_search finds real new
 * releases / similar artists in the same call, so there's no need for an
 * investigate loop or a nested web_lookup agent. The Live model speaks the
 * result; a fitting saved pick becomes an offer the driver can accept via the
 * `play_mixtape` tool.
 */
object MusicAgent {

    // Per call, not `by lazy`: see CompanionIdentity.
    private fun agent(context: Context) =
        SubAgent(systemInstruction = system(context), useSearch = true)

    /**
     * Answers [question] with 1-2 new (web-grounded) discoveries plus, when one
     * fits, an offer to play a saved mixtape by name. One-shot: taste + saved
     * library are pre-assembled into the context, so the model has everything it
     * needs plus search in a single round.
     */
    suspend fun recommend(context: Context, question: String): AgentResult {
        val taste = CarToolbelt.musicTasteSummary(context, 90)
        val saved = CarToolbelt.savedMusicSummary(context)
        val ctx = "Listening taste:\n$taste\n\nAlready on the head unit:\n$saved"
        val q = question.ifBlank { "What should I listen to?" }
        return agent(context).askTyped(ctx, q)
    }

    // Identity from CompanionIdentity, never restated here - see its doc.
    private fun system(context: Context) =
        CompanionIdentity.shortClause(context) + " " +
            "You are picking music for the driver - not an outside DJ. You " +
            "are given the driver's listening taste (top artists/tracks, skip rate, night-vs-day " +
            "split) and what's already saved on the head unit (individual tracks and named " +
            "mixtapes). Recommend one or two genuinely NEW discoveries the driver doesn't already " +
            "have - artists or tracks similar to their top plays - and use web search to ground them " +
            "in real, current releases; never recommend something already on the saved-music list as " +
            "'new'. If a saved mixtape fits the moment (the time of night, the taste pattern), name it " +
            "exactly as saved and offer to play it. Be night-drive and city-pop aware when it fits the " +
            "taste profile, but follow the driver's actual taste over any aesthetic default. Keep it " +
            "concise and spoken-friendly: short sentences, plain text only (no markdown, asterisks, " +
            "headings, or bullet characters), since it is read aloud to someone driving."
}
