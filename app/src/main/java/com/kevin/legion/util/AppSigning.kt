package com.kevin.legion.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * The two identifiers third-party consoles key an OAuth/SDK grant to: this
 * build's **package name** and the **SHA-1 fingerprint of the certificate that
 * actually signed it**.
 *
 * Both Spotify (dashboard -> Android Packages, for App Remote's app-to-app
 * bind) and Google Cloud (OAuth client, for Drive) refuse to talk to a build
 * whose package + signing cert pair is not registered with them. CLAUDE.md §2
 * records the consequence as an open finding against clone-and-run: a stranger
 * building from source signs with their own debug cert and is rejected until
 * they register their own pair.
 *
 * **Read at runtime from [PackageManager], never hardcoded or read from
 * `local.properties`.** A constant would be a claim about how the APK was
 * signed; this is the signature the installed APK actually carries, so it is
 * correct for a debug build and a release build without either being
 * configured, and it cannot silently drift when the keystore changes. That
 * matters here specifically: debug and release share the applicationId
 * `com.kevin.legion` (no `applicationIdSuffix`), so the fingerprint is the
 * ONLY thing distinguishing them to a console, and pasting the wrong one
 * fails in a way neither console explains.
 */
object AppSigning {
    private const val TAG = "AppSigning"

    /** This build's package name - what a console's "package name" field wants. */
    fun packageName(context: Context): String = context.packageName

    /**
     * Colon-separated uppercase SHA-1 of the signing certificate
     * (`AE:C0:...`), or null if it cannot be read.
     *
     * Uses the API 28+ `signingInfo` path where available and the deprecated
     * `GET_SIGNATURES` below it - minSdk is 24, so the legacy branch is not
     * dead code. Where a build has multiple signers (it does not today) the
     * FIRST is reported, which is what both consoles ask for.
     *
     * Null rather than a thrown exception or a placeholder string: a UI that
     * shows nothing is honest, and one that shows a made-up fingerprint would
     * send the driver to paste a value that can never work.
     */
    fun sha1(context: Context): String? {
        val certificate = firstSignature(context) ?: return null
        return runCatching {
            MessageDigest.getInstance("SHA-1")
                .digest(certificate.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        }.getOrElse {
            Log.w(TAG, "SHA-1 digest failed: ${it.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun firstSignature(context: Context): Signature? = runCatching {
        val pm = context.packageManager
        val pkg = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return@runCatching null
            // apkContentsSigners is the current signer set; signingCertificateHistory
            // is only meaningful for a rotated key, which this app has never done.
            signingInfo.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
    }.getOrElse {
        Log.w(TAG, "Could not read signing certificate: ${it.message}")
        null
    }
}
