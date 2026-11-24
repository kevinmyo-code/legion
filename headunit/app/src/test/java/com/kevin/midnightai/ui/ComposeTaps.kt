package com.kevin.midnightai.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper

/**
 * Reading a rendered Compose screen in a Robolectric JVM test: what is on it, and tapping it.
 *
 * Why by semantics and not `createComposeRule`: these are plain unit tests (`app/src/test`), so
 * there is no instrumentation and no `ComposeTestRule`. Walking the semantics tree of the
 * activity's own window is what is left, and it is enough to prove the two things a launcher owes:
 * a tap ran, and words appeared.
 */
internal object ComposeTaps {
    /**
     * Every on-screen text whose own node or nearest ancestor carries a click action, by its text.
     * The ancestor matters: a grid cell puts `clickable` on the surrounding Column and the label
     * inside it, so the tappable thing and the words naming it are two different nodes.
     */
    fun clickable(activity: ComponentActivity): Map<String, () -> Unit> {
        val out = linkedMapOf<String, () -> Unit>()
        walk(activity) { text, click -> if (click != null) out[text] = click }
        return out
    }

    /** Every on-screen text, clickable or not, in tree order. */
    fun texts(activity: ComponentActivity): List<String> {
        val out = mutableListOf<String>()
        walk(activity) { text, _ -> out += text }
        return out
    }

    /** Idles the main looper until [until] holds, or gives up. Background work lands on its own
     * thread, so this sleeps between pumps rather than only draining the queue. */
    fun waitFor(times: Int = 50, until: () -> Boolean) {
        repeat(times) {
            if (until()) return
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
    }

    fun idle() = repeat(5) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }

    private fun walk(activity: ComponentActivity, visit: (String, (() -> Unit)?) -> Unit) {
        fun root(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { root(g.getChildAt(it)) } }
        fun node(n: SemanticsNode, inherited: (() -> Boolean)?) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val click = n.config.getOrNull(SemanticsActions.OnClick)?.action ?: inherited
            if (text != null) visit(text, click?.let { action -> { action() } })
            n.children.forEach { node(it, click) }
        }
        node(root(activity.window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode, null)
    }
}
