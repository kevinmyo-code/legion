package com.kevin.legion.clock

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * The ring's sound and buzz (ticket 02). The phone's default alarm sound on the ALARM stream
 * (`USAGE_ALARM`, which Quiet mode lets through), looping, ramped from quiet to full over
 * [ClockDefaults.RAMP_SECONDS], with a repeating vibration.
 *
 * **An alarm stream at 0 is raised to half for the ring and put back after** - the same reasoning
 * as `QuietMode.RESTORED_STREAMS`: an alarm that cannot be heard is the one failure here that costs
 * more than annoyance. Put back only if nobody moved it meanwhile.
 *
 * **Before the first unlock the ringtone may be unreadable** (media storage is credential
 * encrypted), so any failure to play it falls back to a [ToneGenerator] beep on the same stream.
 */
class AlarmSound(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audio: AudioManager get() = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var raisedTo: Int? = null
    private var previousVolume: Int? = null
    private var focus: AudioFocusRequest? = null
    private var playing = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start() {
        if (playing) return
        playing = true
        raiseIfSilent()
        requestFocus()
        if (!playRingtone()) beep()
        vibrate()
    }

    fun stop() {
        if (!playing) return
        playing = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { tone?.release() }
        tone = null
        runCatching { context.getSystemService(Vibrator::class.java)?.cancel() }
        abandonFocus()
        restoreVolume()
    }

    private fun playRingtone(): Boolean = runCatching {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val p = MediaPlayer()
        p.setAudioAttributes(attributes)
        p.setDataSource(context, uri)
        p.isLooping = true
        p.prepare()
        p.setVolume(RAMP_FLOOR, RAMP_FLOOR)
        p.start()
        player = p
        ramp(1)
        true
    }.getOrElse {
        Log.w(TAG, "alarm sound unavailable, beeping instead", it)
        runCatching { player?.release() }
        player = null
        false
    }

    /** Steps the player's volume from [RAMP_FLOOR] to 1 over the ramp, once a second. */
    private fun ramp(step: Int) {
        val p = player ?: return
        val v = (RAMP_FLOOR + (1f - RAMP_FLOOR) * step / ClockDefaults.RAMP_SECONDS).coerceAtMost(1f)
        runCatching { p.setVolume(v, v) }
        if (step < ClockDefaults.RAMP_SECONDS) handler.postDelayed({ ramp(step + 1) }, ClockDefaults.SECOND_MS)
    }

    private fun beep() {
        val t = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull() ?: return
        tone = t
        val loop = object : Runnable {
            override fun run() {
                if (!playing) return
                runCatching { t.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS) }
                handler.postDelayed(this, ClockDefaults.SECOND_MS)
            }
        }
        handler.post(loop)
    }

    // vibrate(effect, attributes) is the API 26-32 form; 33's VibrationAttributes adds nothing here.
    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, 0), attributes)
            } else {
                v.vibrate(PATTERN, 0)
            }
        }
    }

    private fun raiseIfSilent() = runCatching {
        val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        if (current > 0) return@runCatching
        val half = (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) + 1) / 2
        audio.setStreamVolume(AudioManager.STREAM_ALARM, half, 0)
        previousVolume = current
        raisedTo = half
    }

    private fun restoreVolume() {
        val raised = raisedTo ?: return
        val previous = previousVolume ?: return
        runCatching {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == raised) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, previous, 0)
            }
        }
        raisedTo = null
        previousVolume = null
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build()
        focus = r
        runCatching { audio.requestAudioFocus(r) }
    }

    private fun abandonFocus() {
        val r = focus ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching { audio.abandonAudioFocusRequest(r) }
        focus = null
    }

    private companion object {
        const val TAG = "AlfredClock"
        const val RAMP_FLOOR = 0.05f
        const val BEEP_MS = 600
        val PATTERN = longArrayOf(0, 600, 400, 600, 1_000)
    }
}
