package com.kevin.legion.browser

import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** What the browser screen draws. [blocked] puts the feed blocker's screen over the page. */
data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blocked: FeedBlock? = null,
    val notice: String? = null,
)

/**
 * LEGION's browser (ADR 0071): the one owner of the WebView, for the voice tool and the screen alike
 * (ADR 0035: both paths call this). One instance, held by `MidnightApplication.browser`.
 *
 * The WebView belongs to `BrowserActivity`, which attaches it here and detaches it when it goes. Every
 * WebView call runs on the main thread; the suspend functions wait for WebView's own callbacks
 * (page finished, script result) rather than guessing, so "opened" and "tapped" stand on a result.
 *
 * The profile: WebView's own cookie jar and storage, persisted by WebView across restarts, third-party
 * cookies on (Chrome's default; single sign-on redirects need them), JavaScript on, file and content
 * access off, no pop-up windows, downloads refused. The WebView is important for autofill, so
 * Android's autofill service (Google Password Manager) fills passwords; nothing here reads them.
 */
@Suppress("TooManyFunctions") // the screen's buttons, the tool's seven actions, and WebView's callbacks
class BrowserController(
    private val launchScreen: () -> Unit,
    private val gate: (String) -> FeedBlock?,
) : BrowserPort {
    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The screen collects this and finishes: the voice `close`. */
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    private var web: WebView? = null
    private var attached = CompletableDeferred<WebView>()
    private var detached: CompletableDeferred<Unit>? = null
    private var loadWaiter: CompletableDeferred<LoadOutcome>? = null
    private var navStarted: CompletableDeferred<Unit>? = null
    private var mainFrameStatus: Int? = null

    @Volatile
    override var currentUrl: String? = null
        private set

    // ---- the screen ---------------------------------------------------------------------------

    /** Sets up and attaches a new WebView. Main thread, from the screen's AndroidView factory. */
    fun attach(view: WebView) {
        configure(view)
        web = view
        currentUrl = view.url
        if (!attached.isCompleted) attached.complete(view)
    }

    /** The screen is going. Main thread. Anything waiting on the page hears "not open". */
    fun detach(view: WebView) {
        if (web !== view) return
        CookieManager.getInstance().flush()
        web = null
        currentUrl = null
        attached = CompletableDeferred()
        loadWaiter?.complete(LoadOutcome.NotOpen)
        detached?.complete(Unit)
        _state.value = BrowserUiState()
    }

    /** Typed into the address bar. The same load and feed check the voice `open` uses. */
    fun submit(text: String) {
        val url = BrowserUrl.target(text) ?: return
        web?.let { startLoad(it, url) }
    }

    fun goBack() {
        web?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        web?.takeIf { it.canGoForward() }?.goForward()
    }

    fun reload() {
        web?.reload()
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** "Go back" on the block screen: drop it, and leave the blocked page if it had started to show. */
    fun dismissBlock() {
        _state.update { it.copy(blocked = null) }
        val view = web ?: return
        if (gate(view.url.orEmpty()) != null) {
            if (view.canGoBack()) view.goBack() else view.loadUrl(BLANK)
        }
    }

    /** A 15 minute pass was granted on the block screen: load what was blocked. The gate re-checks it. */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    fun openAfterPass(): Boolean {
        val block = _state.value.blocked ?: return false
        val view = web ?: return false
        _state.update { it.copy(blocked = null) }
        startLoad(view, block.url)
        return true
    }

    /** The screen came back to the front: a pass may have run out while it was away. */
    fun recheck() {
        val view = web ?: return
        gate(view.url.orEmpty())?.let { showBlock(view, it) }
    }

    // ---- the voice tool ([BrowserPort]) --------------------------------------------------------

    override suspend fun open(url: String): LoadOutcome = onMain {
        launchScreen()
        val view = web ?: withTimeoutOrNull(ATTACH_MS) { attached.await() } ?: return@onMain LoadOutcome.NotOpen
        awaitLoad { startLoad(view, url) }
    }

    override suspend fun snapshot(): PageSnapshot? = onMain {
        val view = web ?: return@onMain null
        PageSnapshot.parse(evaluate(view, PageScripts.SNAPSHOT))
    }

    override suspend fun click(n: Int): ClickOutcome = onMain {
        val view = web ?: return@onMain ClickOutcome.NotOpen
        val (result, after) = actThenWatch { unquote(evaluate(view, PageScripts.click(n))) }
        if (result == "ok") ClickOutcome.Clicked(after) else ClickOutcome.Missing
    }

    override suspend fun type(n: Int, text: String, submit: Boolean): TypeOutcome = onMain {
        val view = web ?: return@onMain TypeOutcome.NotOpen
        val (result, after) = actThenWatch { unquote(evaluate(view, PageScripts.type(n, text, submit))) }
        when (result) {
            "ok" -> TypeOutcome.Typed(after)
            "password" -> TypeOutcome.PasswordRefused
            "notfield" -> TypeOutcome.NotAField
            else -> TypeOutcome.Missing
        }
    }

    override suspend fun scroll(direction: ScrollDirection): ScrollOutcome? = onMain {
        val view = web ?: return@onMain null
        val json = unquote(evaluate(view, PageScripts.scroll(direction))) ?: return@onMain ScrollOutcome(false, false)
        runCatching { JSONObject(json) }.getOrNull()
            ?.let { ScrollOutcome(it.optBoolean("atTop"), it.optBoolean("atEnd")) }
            ?: ScrollOutcome(atTop = false, atEnd = false)
    }

    override suspend fun back(): LoadOutcome = onMain {
        val view = web ?: return@onMain LoadOutcome.NotOpen
        if (!view.canGoBack()) return@onMain LoadOutcome.Failed("there is no earlier page")
        awaitLoad { view.goBack() }
    }

    override suspend fun close(): Boolean = onMain {
        if (web == null) return@onMain true
        val gone = CompletableDeferred<Unit>()
        detached = gone
        _closeRequests.tryEmit(Unit)
        withTimeoutOrNull(CLOSE_MS) { gone.await() } != null
    }

    // ---- inside ------------------------------------------------------------------------------

    private fun startLoad(view: WebView, url: String) {
        if (!BrowserUrl.isWebUrl(url)) {
            loadWaiter?.complete(LoadOutcome.Failed("only web pages open here"))
            return
        }
        val block = gate(url)
        if (block != null) {
            showBlock(view, block)
            return
        }
        _state.update { it.copy(blocked = null, notice = null) }
        view.loadUrl(url)
    }

    private fun showBlock(view: WebView, block: FeedBlock) {
        view.stopLoading()
        _state.update { it.copy(blocked = block, loading = false) }
        loadWaiter?.complete(LoadOutcome.Blocked(block))
    }

    /** Runs [start] and waits for the page it begins to finish, fail, or be blocked. */
    private suspend fun awaitLoad(start: () -> Unit): LoadOutcome {
        val waiter = CompletableDeferred<LoadOutcome>()
        loadWaiter?.complete(LoadOutcome.TimedOut)
        loadWaiter = waiter
        mainFrameStatus = null
        return try {
            start()
            withTimeoutOrNull(LOAD_MS) { waiter.await() } ?: LoadOutcome.TimedOut
        } finally {
            if (loadWaiter === waiter) loadWaiter = null
        }
    }

    /**
     * Runs a click or type, then watches briefly for the page load it may have started: none within
     * [NAV_START_MS] means it stayed on this page.
     */
    @Suppress("ReturnCount") // guard clauses, one worded exit each
    private suspend fun actThenWatch(act: suspend () -> String?): Pair<String?, LoadOutcome?> {
        val waiter = CompletableDeferred<LoadOutcome>()
        val started = CompletableDeferred<Unit>()
        loadWaiter?.complete(LoadOutcome.TimedOut)
        loadWaiter = waiter
        navStarted = started
        mainFrameStatus = null
        try {
            val result = act()
            if (result != "ok") return result to null
            withTimeoutOrNull(NAV_START_MS) {
                while (!started.isCompleted && !waiter.isCompleted) delay(POLL_MS)
            }
            if (!started.isCompleted && !waiter.isCompleted) return result to null
            return result to (withTimeoutOrNull(LOAD_MS) { waiter.await() } ?: LoadOutcome.TimedOut)
        } finally {
            if (loadWaiter === waiter) loadWaiter = null
            if (navStarted === started) navStarted = null
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? = withTimeoutOrNull(SCRIPT_MS) {
        suspendCancellableCoroutine { cont -> view.evaluateJavascript(script) { if (cont.isActive) cont.resume(it) } }
    }

    private fun refreshNav(view: WebView) = _state.update {
        it.copy(
            url = view.url.orEmpty().takeUnless { u -> u == BLANK }.orEmpty(),
            title = view.title.orEmpty(),
            canGoBack = view.canGoBack(),
            canGoForward = view.canGoForward(),
        )
    }

    @Suppress("DEPRECATION") // allowFileAccess is the documented switch; its replacement is the default
    private fun configure(view: WebView) {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        view.setDownloadListener { _, _, _, _, _ ->
            _state.update { it.copy(notice = "Downloads are off in LEGION's browser, so nothing was downloaded.") }
        }
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
    }

    private inner class Client : WebViewClient() {
        @Suppress("ReturnCount") // guard clauses, one worded exit each
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (!BrowserUrl.isWebUrl(url)) {
                val scheme = request.url.scheme
                val words = "That link opens another app ($scheme:), which this browser does not do."
                _state.update { it.copy(notice = words) }
                return true
            }
            val block = gate(url) ?: return false
            showBlock(view, block)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            currentUrl = url
            navStarted?.complete(Unit)
            _state.update { it.copy(loading = true, progress = 0) }
            gate(url.orEmpty())?.let { showBlock(view, it) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            currentUrl = view.url
            refreshNav(view)
            _state.update { it.copy(loading = false, progress = PROGRESS_DONE) }
            if (_state.value.blocked != null || url.isNullOrEmpty() || url == BLANK) return
            loadWaiter?.complete(LoadOutcome.Loaded(url, view.title.orEmpty(), mainFrameStatus))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            currentUrl = url
            refreshNav(view)
            gate(url.orEmpty())?.let { showBlock(view, it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            val reason = error.description?.toString().orEmpty().ifBlank { "the page could not be reached" }
            _state.update { it.copy(loading = false, notice = "The page did not load: $reason") }
            loadWaiter?.complete(LoadOutcome.Failed(reason))
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame) mainFrameStatus = response.statusCode
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            loadWaiter?.complete(LoadOutcome.Failed("the page crashed"))
            _closeRequests.tryEmit(Unit)
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _state.update { it.copy(progress = newProgress) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            _state.update { it.copy(title = title.orEmpty()) }
        }
    }

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    companion object {
        const val ATTACH_MS = 6_000L
        const val LOAD_MS = 20_000L
        private const val NAV_START_MS = 1_200L
        private const val POLL_MS = 50L
        private const val SCRIPT_MS = 5_000L
        private const val CLOSE_MS = 3_000L
        private const val PROGRESS_DONE = 100
        private const val BLANK = "about:blank"

        /** evaluateJavascript hands back a JSON-encoded value; a string comes quoted. */
        internal fun unquote(evaluated: String?): String? =
            runCatching { JSONTokener(evaluated ?: return null).nextValue() as? String }.getOrNull()
    }
}
