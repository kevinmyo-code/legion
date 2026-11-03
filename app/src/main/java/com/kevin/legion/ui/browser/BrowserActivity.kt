package com.kevin.legion.ui.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.kevin.legion.MidnightApplication
import com.kevin.legion.browser.FeedBlock
import com.kevin.legion.ui.MainActivity
import com.kevin.legion.ui.dumb.DumbBounceScreen
import com.kevin.legion.ui.dumb.FeedBlockedScreen
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.launch

/**
 * LEGION's browser screen (ADR 0071). Its own activity and task, so the page survives between voice
 * commands and whatever LEGION shows meanwhile. Reads no extras: the voice tool hands it pages through
 * [com.kevin.legion.browser.BrowserController], so the exported launcher entry ("Browser") can carry
 * nothing in from another app.
 */
class BrowserActivity : ComponentActivity() {
    private val app get() = application as MidnightApplication
    private val model: BrowserViewModel by viewModels { BrowserViewModel.factory(app.browser) }
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = app.browser
        lifecycleScope.launch { controller.closeRequests.collect { finish() } }
        setContent {
            SoftTheme {
                val state by model.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.blocked == null) {
                    if (state.canGoBack) model.back() else finish()
                }
                BrowserScreen(
                    state = state,
                    actions = BrowserActions(
                        onSubmit = model::submit,
                        onBack = model::back,
                        onForward = model::forward,
                        onReload = model::reload,
                        onClose = ::finish,
                        onClearNotice = model::clearNotice,
                    ),
                    page = {
                        AndroidView(
                            factory = { context -> WebView(context).also { web = it; controller.attach(it) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    blocked = { BlockedPage(it) },
                )
            }
        }
    }

    /** The feed blocker's own screens and words (ADR 0062/0066), drawn inside the browser. */
    @Composable
    private fun BlockedPage(block: FeedBlock) {
        val graph = app.dumb
        val pkg = block.site.packageName
        val label = graph.appLabel(pkg).takeUnless { it == pkg } ?: block.site.label
        if (block.matrix) {
            DumbBounceScreen(
                appLabel = label,
                onBack = model::dismissBlock,
                onLeave = {
                    graph.controller.requestLeave()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
            )
        } else {
            FeedBlockedScreen(
                appLabel = label,
                pass = graph.feedPass,
                packageName = pkg,
                onBack = {
                    graph.feedPass.cancelRequest(pkg)
                    model.dismissBlock()
                },
                onOpen = model::openAfterPass,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        app.browser.recheck()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web?.let {
            app.browser.detach(it)
            it.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, BrowserActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
