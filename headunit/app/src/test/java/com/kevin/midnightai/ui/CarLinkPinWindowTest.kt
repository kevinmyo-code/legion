package com.kevin.midnightai.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kevin.midnightai.link.CarLink
import com.kevin.midnightai.link.LinkKeySource
import com.kevin.midnightai.link.LinkKeys
import com.kevin.midnightai.link.LinkStatus
import com.kevin.midnightai.link.OwnerPin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Review round 5964b74c, B1: the owner PIN must hold from the first frame of the Car link screen.
 * The screen used to learn "a PIN is set" only after a background step that first reads the
 * Keystore, so a long press in that window got Forget with no PIN. Here the Keystore read is held
 * open on purpose (a slow keymaster) while HOLD TO FORGET is long-pressed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w800dp-h456dp-land-mdpi")
class CarLinkPinWindowTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val release = CountDownLatch(1)

    private inner class SlowKeystore : LinkKeySource {
        @Volatile var slow = false
        override fun hasRecord() = true
        override fun keys(): LinkKeys {
            if (slow) release.await(10, TimeUnit.SECONDS)
            return LinkKeys(ByteArray(32) { 1 }, null, LinkStatus.PAIRED)
        }
        override fun commitPending(): String? = null
        override fun dropPending() {}
        override fun forget() {}
        override fun phoneName(): String? = null
    }

    @Before fun reset() = CarLink.resetForTest()
    @After fun after() {
        release.countDown()
        CarLink.resetForTest()
        context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun mergedNodes(activity: ComponentActivity): List<SemanticsNode> {
        fun find(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val root = find(activity.window.decorView) ?: return emptyList()
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            out += n
            n.children.forEach(::walk)
        }
        walk(root.semanticsOwner.rootSemanticsNode)
        return out
    }

    private fun texts(activity: ComponentActivity): List<String> =
        mergedNodes(activity).flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    @Test fun b1_aLongPressBeforeThePinStateIsReadStillAsksForThePin() {
        // A PIN is set (its sealed record exists).
        context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).edit().putString("pin_enc", "sealed").commit()
        val store = SlowKeystore()
        CarLink.store = store
        CarLink.refresh()
        store.slow = true // from here every Keystore read hangs until released
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { CarLinkSection(activity, onBack = {}) }
        repeat(5) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }

        val forget = mergedNodes(activity).first { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "HOLD TO FORGET" } &&
                n.config.getOrNull(SemanticsActions.OnLongClick) != null
        }
        forget.config[SemanticsActions.OnLongClick].action!!.invoke()
        repeat(5) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }

        val shown = texts(activity)
        assertTrue("Forget ran without the PIN: $shown", shown.any { it == "Owner PIN to forget the phone." })
    }
}
