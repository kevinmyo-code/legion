package com.kevin.midnightai.ui

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kevin.midnightai.link.PairingSession.Reason
import com.kevin.midnightai.link.PairingSession.Stage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Recheck c53f1fc3 (render tests PARTLY): every pairing stage composed inside the real Settings pane
 * chrome at the unit's 800x456, and the line or button the driver needs is ON SCREEN without
 * scrolling (the pane body scrolls, so "composed" alone proves nothing).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class PairingStagesRenderTest {

    private fun render(stage: Stage): Pair<ComponentActivity, Map<String, Rect>> {
        val qr = Bitmap.createBitmap(440, 440, Bitmap.Config.ARGB_8888)
        return render { PairingStageView(stage, qr, note = null, onCancel = {}, onCode = {}, onFinished = {}) }
    }

    private fun render(body: @androidx.compose.runtime.Composable () -> Unit): Pair<ComponentActivity, Map<String, Rect>> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            SectionScaffold("Car link", onBack = {}) { body() }
        }
        repeat(5) { ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS) }
        fun find(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val out = linkedMapOf<String, Rect>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { out[it.text] = n.boundsInWindow }
            n.children.forEach { walk(it) }
        }
        walk(find(activity.window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode)
        return activity to out
    }

    private fun assertOnScreen(stage: Stage, text: (String) -> Boolean) = assertOnScreen(stage.toString(), render(stage), text)

    private fun assertOnScreen(what: String, rendered: Pair<ComponentActivity, Map<String, Rect>>, text: (String) -> Boolean) {
        val (activity, texts) = rendered
        val w = activity.window.decorView.width.toFloat()
        val h = activity.window.decorView.height.toFloat()
        val hit = texts.entries.firstOrNull { text(it.key) }
        assertTrue("$what: not composed; texts ${texts.keys}", hit != null)
        val r = hit!!.value
        assertTrue("$what: '${hit.key}' at $r is off the ${w}x$h screen", r.top >= 0f && r.bottom <= h && r.left >= 0f && r.right <= w)
    }

    @Test fun waitingForThePhone_cancelIsOnScreen() = assertOnScreen(Stage.WaitingForPhone) { it == "CANCEL" }

    @Test fun enterCode_okIsOnScreen() = assertOnScreen(Stage.EnterCode(2)) { it == "OK" }

    @Test fun receiving_isOnScreen() = assertOnScreen(Stage.Receiving) { it.startsWith("Numbers match") }

    @Test fun awaitingThePhone_isOnScreen() = assertOnScreen(Stage.AwaitingPhone) { it.startsWith("Saved. Waiting") }

    @Test fun pairedUnconfirmed_doneIsOnScreen() =
        assertOnScreen(Stage.Paired("Test phone", listOf("engine sign-in"), confirmed = false)) { it == "DONE" }

    /** Owner PIN (2026-11-20): the PIN pad and its CANCEL fit the unit's screen like the pairing pad. */
    @Test fun ownerPinPrompt_okAndCancelAreOnScreen() {
        val pin = com.kevin.midnightai.link.OwnerPin(org.robolectric.RuntimeEnvironment.getApplication())
        val rendered = render { OwnerPinPrompt(pin, prompt = "Owner PIN to forget the phone.", onOk = {}, onCancel = {}) }
        assertOnScreen("pin prompt", rendered) { it == "OK" }
        assertOnScreen("pin prompt", rendered) { it == "CANCEL" }
    }

    @Test fun ownerPinSetup_okAndNotNowAreOnScreen() {
        val pin = com.kevin.midnightai.link.OwnerPin(org.robolectric.RuntimeEnvironment.getApplication())
        val rendered = render { OwnerPinSetup(pin, onDone = {}, onCancel = {}) }
        assertOnScreen("pin setup", rendered) { it == "OK" }
        assertOnScreen("pin setup", rendered) { it == "NOT NOW" }
        assertOnScreen("pin setup", rendered) { it.startsWith("Choose an owner PIN") }
    }

    @Test fun failed_backIsOnScreen() = assertOnScreen(Stage.Failed(Reason.COMMIT_FAILED)) { it == "BACK" }
}
