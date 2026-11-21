package com.kevin.legion.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ML Kit's terms say it reports usage metrics to Google, through Google's data-transport library
 * (a job service, an alarm receiver and a backend discovery service it adds to the app). The app's
 * manifest removes all of them. This reads the MERGED manifest (what the APK carries) through the
 * package manager and fails if any data-transport component came back, for example with an ML Kit
 * upgrade.
 */
@RunWith(RobolectricTestRunner::class)
class CarLinkNoUsageReportingTest {
    @Suppress("DEPRECATION") // the flags form is the one every API level answers
    private fun componentNames(): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return listOfNotNull(info.services, info.receivers, info.providers, info.activities)
            .flatMap { list -> list.map { it.name } }
    }

    @Test fun `the merged manifest was read at all`() {
        assertTrue(
            "ML Kit's own component discovery is expected in the merged manifest",
            "com.google.mlkit.common.internal.MlKitComponentDiscoveryService" in componentNames(),
        )
    }

    @Test fun `no Google data-transport component is in the app`() {
        val transport = componentNames().filter { it.startsWith("com.google.android.datatransport") }
        assertTrue("usage reporting components present: $transport", transport.isEmpty())
    }
}
