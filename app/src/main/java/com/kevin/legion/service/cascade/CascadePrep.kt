package com.kevin.legion.service.cascade

import android.content.Context
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.LiveToolbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.time.LocalDate

/**
 * The conversation's prompt, built AHEAD of the conversation (voice-fast step 2, 2026-11-15): the
 * cacheable instruction and the tool declarations exist when a conversation opens, so neither the
 * greeting nor the first answer waits for them (the 11-15 phone logs showed ~6.5 s of prompt build
 * on the open path). Built at app start, at a wake word's onset, and after every conversation (for
 * the next one); dropped when the base instruction is invalidated (a car switch, a new Alfred pack).
 *
 * Only the CACHEABLE half is prepared here (and the date paragraph, which is checked against today's
 * date): the live context (clock, place, continuity) stays per conversation, because it must be
 * current when the conversation opens. Free: no network call to Gemini, no cache is created here
 * (that stays with a conversation that listens, see [ContextCacheStore]).
 */
internal object CascadePrep {
    data class Prepared(val split: CascadePrompt.Split, val prefix: CascadePrefix, val builtAtMs: Long, val day: LocalDate)

    /** A prepared prompt older than this is rebuilt before use (the base instruction's own TTL is 2 min). */
    const val FRESH_MS = 5 * 60_000L

    /** A build this recent is not repeated (onset, open and close can all ask within a second). */
    const val REBUILD_GAP_MS = 30_000L

    @Volatile private var ready: Prepared? = null
    @Volatile private var building: Deferred<Prepared?>? = null
    @Volatile private var lastStartAtMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pure: may [p] be used at [now] on [today]? */
    fun usable(p: Prepared?, now: Long, today: LocalDate): Boolean =
        p != null && now - p.builtAtMs < FRESH_MS && p.day == today

    /** The prepared prompt if it may be used now, else null. */
    fun fresh(now: Long = System.currentTimeMillis(), today: LocalDate = LocalDate.now()): Prepared? =
        ready?.takeIf { usable(it, now, today) }

    /** A conversation built the prompt itself (nothing prepared): kept for the next one. */
    fun offer(split: CascadePrompt.Split, prefix: CascadePrefix) {
        ready = Prepared(split, prefix, System.currentTimeMillis(), LocalDate.now())
    }

    /** The base instruction changed (car switch, Alfred pack): the next conversation builds anew. */
    fun invalidate() {
        ready = null
        lastStartAtMs = 0L
    }

    /** Starts a build in the background unless one is running or ran in the last [REBUILD_GAP_MS]. Never throws. */
    fun prepare(context: Context, reason: String) {
        val now = System.currentTimeMillis()
        if (building?.isActive == true || now - lastStartAtMs < REBUILD_GAP_MS) return
        lastStartAtMs = now
        val ctx = context.applicationContext
        building = scope.async {
            val t = System.currentTimeMillis()
            runCatching {
                val split = CascadePrompt.split(AriaBrain.get(ctx).buildBaseInstruction())
                val prefix = CascadePrefix(CascadeSession.MODEL, split.cacheable, CascadeRules.declarations(LiveToolbox.declarations()))
                val before = ready?.prefix?.key
                Prepared(split, prefix, System.currentTimeMillis(), LocalDate.now()).also {
                    ready = it
                    LiveLatency.log(
                        LiveLatency.TAG,
                        "prep_prompt_ms=${System.currentTimeMillis() - t} reason=$reason " +
                            "changed=${before != null && before != prefix.key}",
                    )
                }
            }.getOrElse {
                LiveLatency.log(LiveLatency.TAG, "prep_prompt failed reason=$reason error=${it.javaClass.simpleName}")
                null
            }
        }
    }
}
