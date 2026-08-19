package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * LEGION's OWN observation of what played, on this device - v26, ticket 05
 * (`.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`, Kevin
 * 2026-08-18: "can we look up our favorite or recent albums?"). Deliberately NOT a mirror of
 * Spotify's own recently-played history ([com.kevin.legion.media.RecentlyPlayedTrack],
 * `SpotifyWebApi.getRecentlyPlayed`) - that call already answers "what did Spotify see me
 * play, anywhere" and needs no local copy. This table answers a genuinely different question,
 * "what did LEGION itself observe playing" - which is real signal about drive-time listening
 * that Spotify's account-wide history cannot isolate, not the retired music-taste ledger
 * returning under a new name (that ledger scored tracks against a taste model and drove
 * mixtape generation; this is a flat, unscored observation log with no downstream consumer
 * but a spoken read-back).
 *
 * **The honesty rule this table exists to serve** (CLAUDE.md §4 rule 5's data-anchoring thesis,
 * carried into `browse_my_music`'s `legion_history` source in `service/LiveToolbox.kt`): any
 * "favourite" or "most played" LEGION reports from these rows is LEGION's OWN inference from
 * what it happened to observe, not a figure Spotify published, and every surface reading this
 * table must say so in words - never present it as Spotify's own ranking.
 *
 * **Written by [com.kevin.legion.media.NowPlayingController]** (see its own doc comment) the
 * moment the observed track CHANGES - never on a timer, and never a duplicate row for the same
 * track re-observed inside [com.kevin.legion.media.NowPlayingController.PLAY_HISTORY_DEDUP_WINDOW_MS].
 * That dedup window exists because [com.kevin.legion.media.NowPlayingController.updateState]
 * re-fires on every `onPlaybackStateChanged` callback (play/pause/seek), not only on an actual
 * track change - without it, pausing and resuming the same song would log it twice.
 *
 * **Never leaves the device** (CLAUDE.md §4 rule 7 / §7's no-backend rule): this is observational
 * data about Kevin's own listening on his own phone, has no sync registry entry, and part C's
 * `browse_my_music` tool is the only reader. Do not add a sync path for this table without a
 * fresh decision - CLAUDE.md §2's carry-over ruling already retired the old music-taste ledger
 * for being exactly this kind of thing, and syncing a private listening log across two phones
 * sharing one Drive `appDataFolder` is a different, unasked question.
 */
@Entity(tableName = "music_play_history")
data class MusicPlayHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    /** Spotify URI when the observing session could resolve one, null otherwise - MediaSession
     * metadata from Bluetooth/AVRCP relays frequently carries no URI at all, so this is
     * routinely null even for a genuinely Spotify-sourced track. Never treat null as "not
     * Spotify" - it means "unknown", nothing more. */
    val spotifyUri: String?,
    /** Epoch ms this row was observed - see the class doc for the dedup window this is checked against. */
    val startedAt: Long,
    /** True when [com.kevin.legion.media.SpotifyController.playUri] or
     * [com.kevin.legion.media.MusicController]'s transport calls are what started this specific
     * track (a `play_music`/`control_music` tool call landed just before the change was
     * observed); false when the track change was observed with no LEGION action behind it -
     * i.e. the driver started it themselves, on their phone or in Spotify directly. */
    @ColumnInfo(defaultValue = "0") val startedByLegion: Boolean = false,
)
