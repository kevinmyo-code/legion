package com.kevin.legion.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.kevin.legion.service.AriaForegroundService

/**
 * Where the side key lands when LEGION is the phone's digital assistant (2026-09-27).
 *
 * **Why this is its own activity, and why it draws nothing.** Held while the phone was locked, the
 * side key used to reach [MainActivity], which Android isn't allowed to show over the lock screen.
 * So the request sat parked behind it and fired on the NEXT unlock: Kevin, *"when i long press it
 * starts listening next time i unlock"*. The mic opened minutes after he asked, when he wasn't
 * asking. This activity IS allowed over the lock screen, but only so it can ask Android for the
 * unlock prompt. It renders no content, so no ledger, calendar or list is ever shown on a locked
 * phone. Kevin ruled out talking over the lock screen: *"i dont actually need to talk over the
 * locked screen. so lets add the unlock check."*
 *
 * - Unlocked already: start the one-shot conversation and show LEGION.
 * - Locked: show the unlock prompt. Success starts it. Cancel or error just closes, and nothing is
 *   left parked to fire later.
 */
class AssistActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            startOneShot()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = startOneShot()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun startOneShot() {
        runCatching {
            startService(
                Intent(this, AriaForegroundService::class.java)
                    .setAction(AriaForegroundService.ACTION_ASSIST_ONE_SHOT),
            )
            // Bring LEGION forward so the conversation's caption and state are visible.
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure { Log.w("AssistActivity", "one-shot hand-off failed", it) }
        finish()
    }
}
