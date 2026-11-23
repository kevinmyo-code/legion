package com.kevin.legion.ui.controlroom

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi

/**
 * The confirm in front of every Allow on a deploy, publish, flag or spend (Kevin, 2026-11-22:
 * BIOMETRIC_STRONG or DEVICE_CREDENTIAL). The engine cannot see it; the guarantee rests on this
 * phone, as the ID vault's does (ADR 0076). Decisions and message sends stay one tap.
 *
 * A seam so tests can stand in a fake: [confirm] calls exactly one of [onConfirmed] or [onRefused].
 */
fun interface ActionConfirmer {
    fun confirm(title: String, onConfirmed: () -> Unit, onRefused: (String) -> Unit)
}

/**
 * The platform [BiometricPrompt] (no FragmentActivity, no new dependency, as `VaultBiometric`).
 * Android 11+ only, where `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` can be asked for; older phones
 * are refused in words and nothing is allowed.
 */
class BiometricActionConfirmer(private val activity: Activity) : ActionConfirmer {

    override fun confirm(title: String, onConfirmed: () -> Unit, onRefused: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onRefused(OLD_PHONE)
            return
        }
        val manager = activity.getSystemService(BiometricManager::class.java)
        if (manager == null || manager.canAuthenticate(AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS) {
            onRefused(NO_LOCK)
            return
        }
        show(title, onConfirmed, onRefused)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun show(title: String, onConfirmed: () -> Unit, onRefused: (String) -> Unit) {
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Your fingerprint or PIN allows this once.")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onConfirmed()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onRefused(CANCELLED)
            },
        )
    }

    companion object {
        @Suppress("InlinedApi") // read only on Android 11+, inside the check above
        const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        const val OLD_PHONE = "This needs Android 11 or newer for its fingerprint or PIN check. Nothing was allowed."
        const val NO_LOCK = "This phone has no fingerprint or screen lock set up. Nothing was allowed."
        const val CANCELLED = "Not confirmed. Nothing was allowed."
    }
}
