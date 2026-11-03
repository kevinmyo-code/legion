package com.kevin.legion.browser

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/**
 * The `browser` voice tool (ADR 0071): one declaration, seven actions. Every rule lives here, over
 * [BrowserPort], so each is a unit test: the URL a request becomes, which numbers are still good, the
 * password refusal, and the ask-first handshake.
 *
 * **Outcome words follow results** (CLAUDE.md section 7): "Opened" only on [LoadOutcome.Loaded],
 * "Tapped" only on [ClickOutcome.Clicked]. Every failure says what did NOT happen.
 *
 * **Ask first, enforced here, not only in the description.** A risky click or type returns a question
 * and a one-use token; only a call carrying that token within [CONFIRM_TTL_MS], on the same page, acts,
 * and it acts on what was asked about (the stored element and text), not on new arguments. Any other
 * browser call in between drops the token, so the yes has to be the next thing.
 *
 * The last page read is held in memory for its numbers only and dropped on the next navigation: page
 * text is third-party content and is never stored (CLAUDE.md section 7, `EPISODIC_EXCLUDED_TOOLS`).
 */
@Suppress("TooManyFunctions") // seven actions, the ask-first handshake, and one worded result per outcome
class BrowserTool(
    private val port: BrowserPort,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = { UUID.randomUUID().toString().take(TOKEN_LENGTH) },
) {
    private data class Pending(
        val token: String,
        val action: String,
        val url: String,
        val element: PageElement,
        val text: String?,
        val submit: Boolean,
        val atMs: Long,
    )

    private var lastPage: PageSnapshot? = null
    private var pending: Pending? = null

    private val lock = Mutex()

    /** One call at a time: Live can send two tool calls in one turn, and both read [pending]. */
    suspend fun run(args: JSONObject): JSONObject = lock.withLock { runLocked(args) }

    private suspend fun runLocked(args: JSONObject): JSONObject {
        val action = args.optString("action").trim().lowercase()
        val token = args.optString("confirm").trim()
        val held = pending
        pending = null
        if (token.isNotEmpty()) return confirmed(held, token, action)
        return when (action) {
            "open" -> open(args)
            "read" -> read()
            "click" -> click(args)
            "type" -> type(args)
            "scroll" -> scroll(args)
            "back" -> back()
            "close" -> close()
            else -> fail("Nothing was done: \"$action\" is not a browser action.")
        }
    }

    private suspend fun open(args: JSONObject): JSONObject {
        val kind = if (args.optString("kind").equals("images", ignoreCase = true)) SearchKind.IMAGES else SearchKind.WEB
        val url = BrowserUrl.target(args.optString("text"), kind)
            ?: return fail("Nothing was opened: say a web address or what to search for.")
        lastPage = null
        return loaded(port.open(url), verb = "Opened")
    }

    private suspend fun read(): JSONObject {
        if (port.currentUrl == null) return notOpen("Nothing was read")
        val page = freshSnapshot() ?: return fail("Nothing was read: this page would not let the browser read it.")
        lastPage = page
        return ok("Read the page. Its text is for answering now; never repeat a password or card number.")
            .put("title", page.title)
            .put("url", page.url)
            .put("text", page.text + if (page.textCut) " ... (text cut short)" else "")
            .put("elements", page.elementLines().ifEmpty { "(nothing to tap on screen)" })
            .put("more_elements_below", page.moreElements)
    }

    private suspend fun click(args: JSONObject): JSONObject {
        if (port.currentUrl == null) return notOpen("Nothing was tapped")
        val (page, element) = when (val found = resolve(args)) {
            is Resolved.Found -> found.page to found.element
            is Resolved.Problem -> return fail("Nothing was tapped: ${found.words}")
        }
        AskFirst.forClick(element, page)?.let { return ask(it, draft("click", page, element, null, submit = false)) }
        return doClick(element)
    }

    private suspend fun type(args: JSONObject): JSONObject {
        if (port.currentUrl == null) return notOpen("Nothing was typed")
        val text = args.optString("text")
        if (text.isEmpty()) return fail("Nothing was typed: say what to type.")
        if (!args.has("number")) return fail("Nothing was typed: say which numbered field (read the page for numbers).")
        val (page, element) = when (val found = resolve(args)) {
            is Resolved.Found -> found.page to found.element
            is Resolved.Problem -> return fail("Nothing was typed: ${found.words}")
        }
        if (element.kind == ElementKind.PASSWORD) return passwordRefusal()
        if (element.kind != ElementKind.FIELD) {
            return fail("Nothing was typed: ${element.n} is a ${element.kind.word}, not a text field.")
        }
        val submit = args.optBoolean("submit", false)
        AskFirst.forType(element, page, submit)?.let { return ask(it, draft("type", page, element, text, submit)) }
        return doType(element, text, submit)
    }

    private suspend fun scroll(args: JSONObject): JSONObject {
        val direction = when (args.optString("direction").lowercase()) {
            "up" -> ScrollDirection.UP
            "top" -> ScrollDirection.TOP
            else -> ScrollDirection.DOWN
        }
        val moved = port.scroll(direction) ?: return notOpen("Nothing was scrolled")
        val where = when {
            moved.atEnd -> " That is the bottom of the page."
            moved.atTop -> " That is the top of the page."
            else -> ""
        }
        return ok("Scrolled ${direction.name.lowercase()}.$where Read again for the numbers now on screen.")
    }

    private suspend fun back(): JSONObject {
        lastPage = null
        return loaded(port.back(), verb = "Went back to")
    }

    private suspend fun close(): JSONObject {
        lastPage = null
        if (port.currentUrl == null) return ok("The browser was not open.")
        return if (port.close()) ok("Closed the browser.") else fail("The browser did not close.")
    }

    // ---- ask first --------------------------------------------------------------------------

    private fun draft(action: String, page: PageSnapshot, element: PageElement, text: String?, submit: Boolean) =
        Pending(token = "", action = action, url = page.url, element = element, text = text, submit = submit, atMs = 0L)

    private fun ask(question: String, draft: Pending): JSONObject {
        val token = newToken()
        pending = draft.copy(token = token, atMs = clock())
        val what = if (draft.action == "click") "tapped" else "typed"
        return JSONObject()
            .put("success", false)
            .put("needs_confirmation", true)
            .put("question", question)
            .put("confirm", token)
            .put(
                "message",
                "Nothing was $what yet. Ask the user exactly this: $question Only if their very next reply is " +
                    "yes, call browser again with confirm=\"$token\". Anything else: drop it.",
            )
    }

    private suspend fun confirmed(held: Pending?, token: String, action: String): JSONObject {
        val nothing = "Nothing was done"
        return when {
            held == null || held.token != token ->
                fail("$nothing: that confirmation is not the one just asked for, or it was already used. Ask again.")
            clock() - held.atMs !in 0..CONFIRM_TTL_MS ->
                fail("$nothing: the yes came too late (more than ${CONFIRM_TTL_MS / 1000} seconds). Ask again.")
            action.isNotEmpty() && action != held.action ->
                fail("$nothing: that confirmation was for ${held.action}, not $action.")
            port.currentUrl != held.url ->
                fail("$nothing: the page changed since the question. Read it again.")
            held.action == "click" -> doClick(held.element)
            else -> doType(held.element, held.text.orEmpty(), held.submit)
        }
    }

    // ---- doing ------------------------------------------------------------------------------

    private suspend fun doClick(element: PageElement): JSONObject {
        val name = element.label.ifBlank { "number ${element.n}" }
        return when (val out = port.click(element.n)) {
            is ClickOutcome.Clicked -> afterAction("Tapped \"$name\".", out.after)
            ClickOutcome.Missing -> fail("Nothing was tapped: ${element.n} is no longer on the page. Read it again.")
            ClickOutcome.NotOpen -> notOpen("Nothing was tapped")
        }
    }

    private suspend fun doType(element: PageElement, text: String, submit: Boolean): JSONObject {
        val name = element.label.ifBlank { "field ${element.n}" }
        return when (val out = port.type(element.n, text, submit)) {
            is TypeOutcome.Typed ->
                afterAction("Typed into \"$name\"" + if (submit) " and submitted it." else ".", out.after)
            TypeOutcome.PasswordRefused -> passwordRefusal()
            TypeOutcome.Missing -> fail("Nothing was typed: ${element.n} is no longer on the page. Read it again.")
            TypeOutcome.NotAField -> fail("Nothing was typed: ${element.n} is not a text field.")
            TypeOutcome.NotOpen -> notOpen("Nothing was typed")
        }
    }

    private fun afterAction(done: String, after: LoadOutcome?): JSONObject {
        if (after == null) return ok(done)
        lastPage = null
        return when (after) {
            is LoadOutcome.Loaded ->
                ok("$done Now on \"${after.title}\".").put("title", after.title).put("url", after.url)
            is LoadOutcome.Blocked -> ok("$done The page it led to is blocked: ${blockedWords(after.block)}")
            is LoadOutcome.Failed -> ok("$done The page it led to did not load: ${after.reason}.")
            LoadOutcome.TimedOut -> ok("$done A new page is still loading.")
            LoadOutcome.NotOpen -> ok(done)
        }
    }

    private fun loaded(out: LoadOutcome, verb: String): JSONObject = when (out) {
        is LoadOutcome.Loaded -> {
            val status = out.httpStatus?.takeIf { it >= HTTP_ERROR }
            val msg = if (status != null) {
                "The page loaded, but the site answered with error $status."
            } else {
                "$verb \"${out.title.ifBlank { BrowserUrl.host(out.url) ?: out.url }}\"."
            }
            ok(msg).put("title", out.title).put("url", out.url)
        }
        is LoadOutcome.Blocked -> fail("Nothing was opened: ${blockedWords(out.block)}")
        is LoadOutcome.Failed -> fail("The page did not open: ${out.reason}.")
        LoadOutcome.TimedOut ->
            fail("The page has not finished loading, so it is not open yet. It may still appear on screen.")
        LoadOutcome.NotOpen -> fail("The browser screen did not come up, so nothing was opened.")
    }

    private fun blockedWords(block: FeedBlock): String =
        if (block.matrix) {
            "${block.site.label} is blocked in Matrix mode. The browser shows the block screen."
        } else {
            "${block.site.label} is blocked by the feed blocker. The browser shows the block screen, " +
                "where a 15 minute pass can be asked for by hand."
        }

    // ---- numbers ------------------------------------------------------------------------------

    private sealed interface Resolved {
        data class Found(val page: PageSnapshot, val element: PageElement) : Resolved
        data class Problem(val words: String) : Resolved
    }

    /** A number from the last read on this same page, or words matched against a fresh read. */
    private suspend fun resolve(args: JSONObject): Resolved {
        if (args.has("number")) {
            val n = args.optInt("number", -1)
            val page = lastPage?.takeIf { it.url == port.currentUrl }
                ?: return Resolved.Problem("the numbers come from a read, and this page was not read. Read it first.")
            val element = page.element(n) ?: return Resolved.Problem("there is no number $n on the page that was read.")
            return Resolved.Found(page, element)
        }
        val words = args.optString("text").trim()
        if (words.isEmpty()) return Resolved.Problem("say a number from the page, or the words on the thing to tap.")
        val page = freshSnapshot() ?: return Resolved.Problem("this page would not let the browser read it.")
        lastPage = page
        return when (val m = page.match(words)) {
            is MatchResult.One -> Resolved.Found(page, m.element)
            MatchResult.None -> Resolved.Problem("nothing on screen says \"$words\". Read the page for the numbers.")
            is MatchResult.Several -> Resolved.Problem(
                "several things say \"$words\": " +
                    m.elements.take(MAX_LISTED).joinToString("; ") { "${it.n} ${it.label}" } + ". Say the number.",
            )
        }
    }

    /** A read, stamped with the browser's own URL so a later "same page?" check compares like with like. */
    private suspend fun freshSnapshot(): PageSnapshot? {
        val page = port.snapshot() ?: return null
        return page.copy(url = port.currentUrl ?: page.url)
    }

    private fun passwordRefusal(): JSONObject = fail(
        "Nothing was typed: that is a password field, and passwords are never typed by voice. Android's " +
            "autofill offers the saved password when the field is tapped by hand.",
    )

    private fun notOpen(nothing: String) = fail("$nothing: the browser is not open.")

    private fun ok(message: String) = JSONObject().put("success", true).put("message", message)

    private fun fail(message: String) = JSONObject().put("success", false).put("message", message)

    companion object {
        /** How long a question's yes stays good: long enough for one reply, not for a later one. */
        const val CONFIRM_TTL_MS = 90_000L
        private const val TOKEN_LENGTH = 8
        private const val HTTP_ERROR = 400
        private const val MAX_LISTED = 5
    }
}
