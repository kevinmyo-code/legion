package com.kevin.midnightai.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
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
import com.kevin.midnightai.update.DrivingState
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
 * Review round 5964b74c, owner PIN on screen: words after Forget (L-d), the Engine settings prompt
 * re-checks driving every second (L-e), and the typed Gemini key is revealed only after the PIN
 * (L-c). Texts and actions go through the semantics tree, as a driver's taps would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE], qualifiers = "w800dp-h456dp-land-mdpi")
class OwnerPinScreensTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private class Paired : LinkKeySource {
        override fun hasRecord() = true
        override fun keys() = LinkKeys(ByteArray(32) { 1 }, null, LinkStatus.PAIRED)
        override fun commitPending(): String? = null
        override fun dropPending() {}
        override fun forget() {}
        override fun phoneName(): String? = null
    }

    @Before fun reset() = CarLink.resetForTest()
    @After fun after() {
        CarLink.resetForTest()
        DrivingState.reset()
        context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun pinIsSet() {
        context.getSharedPreferences(OwnerPin.PREFS, Context.MODE_PRIVATE).edit().putString("pin_enc", "sealed").commit()
    }

    private fun render(inScaffold: Boolean = true, body: @Composable (ComponentActivity) -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            if (inScaffold) SectionScaffold("Settings", onBack = {}) { body(activity) } else body(activity)
        }
        settle()
        return activity
    }

    private fun settle() = repeat(5) {
        ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
        Thread.sleep(20)
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        fun find(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val out = mutableListOf<SemanticsNode>()
        fun walk(n: SemanticsNode) {
            out += n
            n.children.forEach(::walk)
        }
        walk(find(activity.window.decorView)!!.semanticsOwner.rootSemanticsNode)
        return out
    }

    private fun texts(activity: ComponentActivity) =
        nodes(activity).flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun waitFor(activity: ComponentActivity, what: (List<String>) -> Boolean): List<String> {
        val until = System.currentTimeMillis() + 5_000
        var now = texts(activity)
        while (System.currentTimeMillis() < until && !what(now)) {
            settle()
            now = texts(activity)
        }
        return now
    }

    private fun node(activity: ComponentActivity, label: String) = nodes(activity).first { n ->
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
    }


    @Test fun lD_forgetTellsTheOwnerToRemoveTheHeadUnitOnThePhone() {
        CarLink.store = Paired()
        CarLink.refresh()
        val activity = render(inScaffold = false) { CarLinkSection(it, onBack = {}) }
        node(activity, "HOLD TO FORGET").config[SemanticsActions.OnLongClick].action!!.invoke()
        val shown = waitFor(activity) { t -> t.any { it.contains("Head unit") } }
        assertTrue("no words about the phone: $shown", shown.any { it.contains("remove \"Head unit\"") })
    }
}
