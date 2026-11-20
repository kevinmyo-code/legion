package com.kevin.midnightai.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kevin.midnightai.link.CarLink
import com.kevin.midnightai.link.LinkKeySource
import com.kevin.midnightai.link.LinkKeys
import com.kevin.midnightai.link.LinkStatus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Recheck: the Car link screen itself composes at the unit's 800x456, in the paired state (both
 * hold buttons on screen) and in the pairing pane's first state ("Starting..."; the Wi-Fi lookup
 * that follows runs off the main thread and does not come back under Robolectric's paused looper,
 * so the QR and digit stages are covered by PairingPadRenderTest, not here). Texts are read from
 * the semantics tree; nothing secret is on either state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class CarLinkScreenRenderTest {

    private class Store(private val status: LinkStatus) : LinkKeySource {
        override fun hasRecord() = status != LinkStatus.NOT_PAIRED
        override fun keys() = LinkKeys(if (status == LinkStatus.PAIRED) ByteArray(32) { 1 } else null, null, status)
        override fun commitPending(): String? = null
        override fun dropPending() {}
        override fun forget() {}
        override fun phoneName(): String? = null
    }

    @Before fun reset() = CarLink.resetForTest()
    @After fun after() = CarLink.resetForTest()

    private fun render(status: LinkStatus): Pair<ComponentActivity, Map<String, Rect>> {
        CarLink.store = Store(status)
        CarLink.refresh()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { CarLinkSection(activity, onBack = {}) }
        val until = System.currentTimeMillis() + 5_000
        var texts = emptyMap<String, Rect>()
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            texts = texts(activity)
            if (texts.keys.any { it.startsWith("Paired") || it.startsWith("Starting") }) break
            Thread.sleep(20)
        }
        return activity to texts
    }

    private fun texts(activity: ComponentActivity): Map<String, Rect> {
        fun find(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val root = find(activity.window.decorView) ?: return emptyMap()
        val out = linkedMapOf<String, Rect>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out[it.text] = n.boundsInWindow }
            n.children.forEach { walk(it) }
        }
        walk(root.semanticsOwner.unmergedRootSemanticsNode)
        return out
    }

    private fun assertOnScreen(activity: ComponentActivity, what: String, r: Rect?) {
        val w = activity.window.decorView.width.toFloat()
        val h = activity.window.decorView.height.toFloat()
        assertTrue("$what missing", r != null)
        assertTrue("$what at $r is off the ${w}x$h screen", r!!.left >= 0f && r.top >= 0f && r.right <= w && r.bottom <= h)
    }

    @Test fun thePairedStateShowsBothHoldButtonsOnScreen() {
        val (activity, texts) = render(LinkStatus.PAIRED)
        assertTrue("texts $texts", texts.keys.any { it.startsWith("Paired") })
        assertOnScreen(activity, "pair again", texts["HOLD TO PAIR AGAIN"])
        assertOnScreen(activity, "forget", texts["HOLD TO FORGET"])
    }

    @Test fun anUnpairedUnitOpensThePairingPane() {
        val (activity, texts) = render(LinkStatus.NOT_PAIRED)
        val line = texts.keys.firstOrNull { it.startsWith("Starting") }
        assertTrue("texts $texts", line != null)
        assertOnScreen(activity, "starting line", texts[line])
    }
}
