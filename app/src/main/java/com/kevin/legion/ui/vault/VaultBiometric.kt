package com.kevin.legion.ui.vault

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.kevin.legion.vault.BiometricUnlock

/**
 * The fingerprint gate in front of every reveal (ADR 0076). The platform [BiometricPrompt], not
 * AndroidX's: it takes any Activity (no FragmentActivity, no new dependency), and every phone that
 * runs this app has it.
 *
 * **Which authenticators (stated, as the brief asks):**
 * - Android 11+ (API 30): `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. A class 3 fingerprint, with the
 *   phone's own PIN as the fallback when the sensor will not read (a wet finger, a lockout). The PIN
 *   adds nothing an attacker lacks: whoever has it can enrol a new fingerprint anyway.
 * - Android 9 and 10 (API 28-29): fingerprint only, with a Cancel button; these versions cannot ask
 *   for a strength class or mix in the PIN without deprecated calls.
 * - Below Android 9: refused in words. The vault does not open.
 *
 * Only [onUnlocked] hands out a [BiometricUnlock]; a cancel or an error hands out a sentence.
 */
object VaultBiometric {
    private const val API_STRONG_OR_PIN = Build.VERSION_CODES.R

    /** Null when the phone can show the prompt; otherwise why not, in words. */
    fun unavailable(activity: Activity): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "The ID vault needs Android 9 or newer for its fingerprint check. Nothing was revealed."
        }
        if (Build.VERSION.SDK_INT >= API_STRONG_OR_PIN) {
            val manager = activity.getSystemService(BiometricManager::class.java) ?: return null
            @Suppress("NewApi") // inside the API 30 check just above
            val code = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            if (code != BiometricManager.BIOMETRIC_SUCCESS) {
                return "This phone has no fingerprint or screen lock set up, so the ID vault cannot open. " +
                    "Set one up in Android's Settings. Nothing was revealed."
            }
        }
        return null
    }

    fun prompt(activity: Activity, label: String, onUnlocked: (BiometricUnlock) -> Unit, onRefused: (String) -> Unit) {
        unavailable(activity)?.let { onRefused(it); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) show(activity, label, onUnlocked, onRefused)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun show(
        activity: Activity,
        label: String,
        onUnlocked: (BiometricUnlock) -> Unit,
        onRefused: (String) -> Unit,
    ) {
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("Open $label")
            .setSubtitle("The value shows only on this screen.")
        if (Build.VERSION.SDK_INT >= API_STRONG_OR_PIN) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setNegativeButton("Cancel", executor) { _, _ -> onRefused(CANCELLED) }
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onUnlocked(BiometricUnlock())
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onRefused(
                    when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED,
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED,
                        -> CANCELLED
                        else -> "The fingerprint check did not pass ($errString). Nothing was revealed."
                    },
                )
            }
            // onAuthenticationFailed (one unrecognised finger) leaves the prompt up to try again.
        }
        builder.build().authenticate(CancellationSignal(), executor, callback)
    }

    const val CANCELLED = "Cancelled. Nothing was revealed."
}
