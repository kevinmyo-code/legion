package com.kevin.legion.update

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The thin PackageInstaller wrapper (alfred-ota ticket 05), on Robolectric's own session shadow:
 * one session for this app's package, the whole file written, committed, and no Android dialog
 * asked for on Android 12+.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class AndroidPackageInstallerGatewayTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `one session for this app, written and committed`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val apk = tmp.newFile("phone.apk").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        val result = AndroidPackageInstallerGateway(app).install(apk, apk.length())

        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        val sessions = app.packageManager.packageInstaller.allSessions
        assertEquals(1, sessions.size)
        assertEquals(app.packageName, sessions.single().appPackageName)
    }
}
