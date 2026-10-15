package com.kevin.midnightai.media

import java.util.concurrent.atomic.AtomicLong

/**
 * Supersedes a slow voice play with whatever the driver chose after it.
 *
 * `play_music` keeps its Spotify attempt running past the tool's time budget (so a play that lands
 * a few seconds late still lands). That late play must not override a NEWER choice: another
 * `play_music`, a switch to the phone, a mixtape. Each voice play takes a [Token]; each newer
 * choice bumps the generation ([supersede]); the play checks [shouldProceed] right before it calls
 * `play()` and again before it claims the speakers (MusicSource -> SPOTIFY).
 */
object PlayRequests {
    private val generation = AtomicLong(0)

    /** One voice play: its generation and the source that owned the speakers when it began. */
    data class Token(val generation: Long, val sourceAtStart: Source)

    /** Starts a new voice play, superseding any older one still running. */
    fun begin(sourceAtStart: Source): Token = Token(generation.incrementAndGet(), sourceAtStart)

    /** The driver picked something else to play (source switch, mixtape): older plays stand down. */
    fun supersede() {
        generation.incrementAndGet()
    }

    fun shouldProceed(token: Token): Boolean = shouldProceed(token, generation.get(), MusicSource.current.value)

    /**
     * Pure: the play may go ahead only while it is still the latest request AND nobody moved the
     * speakers elsewhere since it began. A move TO Spotify is not a change of mind - connecting
     * App Remote flips the source to SPOTIFY by itself - so only a move to some other source
     * counts.
     *
     * Still needed after F9 (2026-10-15), but for a narrower reason. Until then EVERY connect flipped
     * the source to SPOTIFY; now a connect does so only when the phone is the source and is not
     * playing ([SpotifyController.shouldClaimSourceOnConnect]). A play that starts from an idle phone
     * can therefore still see the source become SPOTIFY under it, and that is not a change of mind.
     */
    internal fun shouldProceed(token: Token, latestGeneration: Long, sourceNow: Source): Boolean =
        token.generation == latestGeneration &&
            (sourceNow == token.sourceAtStart || sourceNow == Source.SPOTIFY)
}
