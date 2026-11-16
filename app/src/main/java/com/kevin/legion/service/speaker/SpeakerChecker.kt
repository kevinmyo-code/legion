package com.kevin.legion.service.speaker

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One embedding model: float samples in [-1, 1] at 16 kHz in, a raw embedding out. A fake in tests. */
interface SpeakerEmbedder {
    fun embed(samples: FloatArray): FloatArray
}

/** Whether the model can be used right now. */
sealed interface EmbedderState {
    data class Ready(val embedder: SpeakerEmbedder) : EmbedderState
    data object Loading : EmbedderState
    data object Missing : EmbedderState
}

/** The L2-normalised embedding of one window, after the study's gain normalisation. */
fun SpeakerEmbedder.embedWindow(window: ShortArray): FloatArray =
    SpeakerMath.l2Normalize(embed(SpeakerMath.normalise(window)))

/**
 * Scores one wake window against the voiceprint on [executor], the only thread the native model is
 * ever used on (a JNI compute cannot be interrupted, so a deadline has to be a wait, not a cancel).
 *
 * - [shadow] never waits: the window is already copied, the wake opens as today, the score is
 *   handed to `report` when it is done.
 * - [gated] waits at most its deadline; past it the wake opens and the result says so.
 * - One check at a time. A check that ran past its deadline is still computing, and the next hit
 *   reports [SpeakerResult.Busy] (and opens) instead of queueing behind it.
 */
class SpeakerChecker(
    private val executor: ExecutorService,
    private val embedder: () -> EmbedderState,
    private val voiceprint: () -> FloatArray?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val inFlight = AtomicBoolean(false)

    private sealed interface Pre {
        data class Done(val result: SpeakerResult) : Pre
        class Go(val embedder: SpeakerEmbedder, val voiceprint: FloatArray) : Pre
    }

    fun shadow(window: ShortArray, report: (SpeakerResult) -> Unit) {
        val go = when (val p = precheck(window)) {
            is Pre.Done -> return report(p.result)
            is Pre.Go -> p
        }
        if (!inFlight.compareAndSet(false, true)) return report(SpeakerResult.Busy)
        try {
            executor.execute { report(run(go.embedder, go.voiceprint, window)) }
        } catch (ex: RejectedExecutionException) {
            inFlight.set(false)
            report(SpeakerResult.Failed(ex.javaClass.simpleName))
        }
    }

    @Suppress("ReturnCount") // each early result is one of the fail-open cases, said in words
    fun gated(window: ShortArray, deadlineMs: Long = SpeakerPolicy.CHECK_DEADLINE_MS): SpeakerResult {
        val go = when (val p = precheck(window)) {
            is Pre.Done -> return p.result
            is Pre.Go -> p
        }
        if (!inFlight.compareAndSet(false, true)) return SpeakerResult.Busy
        val future: Future<SpeakerResult> = try {
            executor.submit<SpeakerResult> { run(go.embedder, go.voiceprint, window) }
        } catch (ex: RejectedExecutionException) {
            inFlight.set(false)
            return SpeakerResult.Failed(ex.javaClass.simpleName)
        }
        return try {
            future.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            SpeakerResult.TimedOut(deadlineMs)
        } catch (ex: ExecutionException) {
            SpeakerResult.Failed(ex.cause?.javaClass?.simpleName ?: "Execution")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            SpeakerResult.Failed("Interrupted")
        }
    }

    /** Resolves the voiceprint and the model once, so a check never reads two different states. */
    private fun precheck(window: ShortArray): Pre {
        val vp = voiceprint() ?: return Pre.Done(SpeakerResult.NoVoiceprint)
        return when (val s = embedder()) {
            EmbedderState.Missing -> Pre.Done(SpeakerResult.ModelMissing)
            EmbedderState.Loading -> Pre.Done(SpeakerResult.ModelLoading)
            is EmbedderState.Ready ->
                if (window.isEmpty()) Pre.Done(SpeakerResult.NoAudio) else Pre.Go(s.embedder, vp)
        }
    }

    // A model failure of any kind is a result in words, never a crash of the wake word.
    @Suppress("TooGenericExceptionCaught")
    private fun run(e: SpeakerEmbedder, vp: FloatArray, window: ShortArray): SpeakerResult {
        val t0 = nowMs()
        return try {
            val emb = e.embedWindow(window)
            // A model whose size does not match the voiceprint (a different model file) must not
            // score 0 and be gated as "not him": it is a failure, and the wake opens.
            if (emb.size != vp.size) return SpeakerResult.Failed("DimensionMismatch")
            val score = SpeakerMath.cosine(emb, vp)
            if (score.isNaN()) SpeakerResult.Failed("NaN") else SpeakerResult.Scored(score, nowMs() - t0)
        } catch (t: Throwable) {
            SpeakerResult.Failed(t.javaClass.simpleName)
        } finally {
            inFlight.set(false)
        }
    }
}
