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
// The unit runs Android 8+; at Robolectric's default SDK 21 the pane's Wi-Fi lookup throws and
// takes the composition down with it.
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w800dp-h456dp-land-mdpi")
class CarLinkScreenRenderTest {
    @org.junit.After fun closeActivities() = TestActivities.closeAll()


    private class Store(private val status: LinkStatus) : LinkKeySource {
        override fun hasRecord() = status != LinkStatus.NOT_PAIRED
        override fun keys() = LinkKeys(if (status == LinkStatus.PAIRED) ByteArray(32) { 1 } else null, null, status)
        override fun commitPending(): String? = null
        override fun dropPending() {}
        override fun forget() {}
        override fun phoneName(): String? = null
    }

    private companion object {
        const val PAIR_KEY = "PAIR WITH YOUR PHONE"
    }

    @Before fun reset() = CarLink.resetForTest()
    @After fun after() {
        CarLink.resetForTest()
        com.kevin.midnightai.update.DrivingState.reset()
    }

    private fun render(status: LinkStatus): Pair<ComponentActivity, Map<String, Rect>> {
        CarLink.store = Store(status)
        CarLink.refresh()
        val activity = TestActivities.start()
        activity.setContent { CarLinkSection(activity, onBack = {}) }
        val until = System.currentTimeMillis() + 5_000
        var texts = emptyMap<String, Rect>()
        while (System.currentTimeMillis() < until) {
            ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            texts = texts(activity)
            if (texts.keys.any { it.startsWith("Paired") || it.startsWith("Starting") || it == PAIR_KEY }) break
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

    /** Taps PAIR WITH YOUR PHONE (review N-b: an unpaired unit opens no listener until asked). */
    private fun tapPair(activity: ComponentActivity) {
        fun find(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val nodes = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            nodes += n
            n.children.forEach(::walk)
        }
        walk(find(activity.window.decorView)!!.semanticsOwner.rootSemanticsNode)
        nodes.first { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == PAIR_KEY } &&
                n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null
        }.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke()
        repeat(5) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(20) }
    }

    /** Review N-b: an unpaired unit shows PAIR and opens no pairing listener until the owner taps it. */
    @Test fun anUnpairedUnitOpensNoListenerUntilAsked() {
        val (activity, texts) = render(LinkStatus.NOT_PAIRED)
        assertTrue("a pairing pane opened unasked: ${texts.keys}", texts.keys.none { it.startsWith("Starting") || it.contains("not on Wi-Fi") })
        assertOnScreen(activity, "pair key", texts[PAIR_KEY])
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

    /** Recheck 2faecc19: the QR goes away if the car starts moving mid-pairing (checked on a 1 s tick). */
    @Test fun startingToDriveMidPairingDropsToParkToPair() {
        val (activity, _) = render(LinkStatus.NOT_PAIRED)
        tapPair(activity)
        // Let the pane settle in a stable state (no Wi-Fi under Robolectric), so nothing else
        // recomposes it: only the 1 s driving check can take the pairing pane away.
        var now = texts(activity)
        val settle = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < settle && now.keys.none { it.contains("Wi-Fi") }) {
            ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
            now = texts(activity)
        }
        assertTrue("pane never settled: ${now.keys}", now.keys.any { it.contains("Wi-Fi") })
        com.kevin.midnightai.update.DrivingState.note(30.0)
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && now.keys.none { it.startsWith("Park to pair") }) {
            ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20) // the effect's delay runs on a real-time timer
            now = texts(activity)
        }
        assertTrue("still showing the pairing pane while moving: ${now.keys}", now.keys.any { it.startsWith("Park to pair") })
        assertOnScreen(activity, "park line", now[now.keys.first { it.startsWith("Park to pair") }])
    }

    @Test fun anUnpairedUnitOpensThePairingPaneWhenAsked() {
        val (activity, _) = render(LinkStatus.NOT_PAIRED)
        tapPair(activity)
        // "Starting..." or, once its off-thread step has run, why it cannot start (no Wi-Fi here):
        // either is the pairing pane. Let the layout settle before reading where it is.
        repeat(5) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(20) }
        val texts = texts(activity)
        val line = texts.keys.firstOrNull { it.startsWith("Starting") || it.contains("not on Wi-Fi") }
        assertTrue("texts $texts", line != null)
        assertOnScreen(activity, "pairing pane line", texts[line])
    }
}
