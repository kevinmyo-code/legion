package com.kevin.legion.service.cascade

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.legion.service.MicLevel
import com.kevin.legion.service.MicSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The cascade's microphone: a trimmed copy of Live's capture loop (16 kHz PCM16 mono,
 * VOICE_COMMUNICATION, the platform's echo canceller and noise suppressor), moved out of
 * [CascadeSession] unchanged on 2026-11-15 so the session stays one file. Each buffer goes to
 * [run]'s `onBuffer`; a mic that cannot open or keeps failing ends in `onFailure` with a log token.
 */
internal class CascadeMic(private val ctx: Context) {

    @SuppressLint("MissingPermission") // checked first, below
    @Suppress(
        "LongMethod",
        "NestedBlockDepth",
        "ReturnCount",
        "CyclomaticComplexMethod",
        "LoopWithTooManyJumpStatements",
    ) // a copy of Live's capture loop, trimmed; its shape is Live's
    suspend fun run(onBuffer: (ByteArray, Int) -> Unit, onNoPermission: () -> Unit, onFailure: (String) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            onNoPermission()
            return
        }
        delay(MIC_HANDOFF_MS)
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(INPUT_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, INPUT_RATE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)
        val record = try {
            builder.build()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "AudioRecord build failed: ${e.message}")
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            onFailure("audio_record_init")
            return
        }
        val effects = attachEffects(record.audioSessionId)
        val buffer = ByteArray(CHUNK_BYTES)
        var readErrors = 0
        try {
            record.startRecording()
            Log.d(TAG, "AudioRecord opened (cascade) sessionId=${record.audioSessionId}")
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    Log.w(TAG, "record.read failed: $n")
                    // A mic that keeps failing is a deaf conversation: hand it to Live.
                    if (++readErrors >= MAX_READ_ERRORS) {
                        onFailure("read_error_$n")
                        return
                    }
                    delay(READ_ERROR_BACKOFF_MS)
                    continue
                }
                readErrors = 0
                if (n == 0) continue
                MicLevel.publish(MicSignal.peakAmplitude(buffer, n))
                onBuffer(buffer, n)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "mic loop error: ${e.message}")
            onFailure("loop_${e.javaClass.simpleName}")
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
            Log.d(TAG, "AudioRecord released (cascade)")
        }
    }

    private fun attachEffects(sessionId: Int): List<AudioEffect> {
        val out = mutableListOf<AudioEffect>()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; out.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also { it.enabled = true; out.add(it) }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "CascadeSession"
        private const val INPUT_RATE = 16_000
        private const val CHUNK_BYTES = 3_200

        /** Live's own first-capture hand-off (`GeminiLiveSession.MIC_HANDOFF_MS`). */
        private const val MIC_HANDOFF_MS = 250L
        private const val MAX_READ_ERRORS = 20
        private const val READ_ERROR_BACKOFF_MS = 50L
    }
}
