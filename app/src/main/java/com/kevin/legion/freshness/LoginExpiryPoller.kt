package com.kevin.legion.freshness

/** What the notice for one expired login says. [body] is the server's `sentence`, verbatim. */
data class LoginExpiry(val source: String, val title: String, val body: String)

/**
 * Which expiries already raised a notice: source to the `last_error` it carried. Persisted so a
 * restarted process does not repeat a notice the user already has.
 */
interface ExpirySeenStore {
    fun load(): Map<String, String>
    fun save(seen: Map<String, String>)
}

/**
 * Reads `GET /api/freshness` and raises ONE notice per expiry of a saved login, for any source.
 *
 * **Dedupe key is source + `last_error`.** The server's `last_error` carries the refusal
 * timestamp ("Canvas refused the saved login at 2026-10-04T21:30:15..."), so the same expiry
 * repeating every 30 minutes keeps one key and raises once, while a fresh refusal after a fix
 * carries a new one. When a source's outcome returns to anything other than needs_login its key is
 * cleared, so the next expiry raises even if the error text were ever identical.
 *
 * **Any failure is silence**: no engine, a non-200, an unreadable body. An error notification
 * about a notifier is worse than the thing it reports, and the seen set is left untouched so
 * nothing is lost or doubled.
 *
 * **Silenced means no network and no change.** The seen set is not advanced while silenced, so a
 * user who turns the notices back on while a login is still expired hears about it on the next
 * poll; that is a fact he can verify, not a nag.
 */
class LoginExpiryPoller(
    private val backend: () -> FreshnessBackend?,
    private val seen: ExpirySeenStore,
    private val silenced: () -> Boolean,
    private val raise: (LoginExpiry) -> Unit,
) {
    /** How many notices this poll raised. 0 for every kind of silence. */
    suspend fun pollOnce(): Int {
        if (silenced()) return 0
        val feeds = (backend() ?: return 0).feeds().getOrElse { return 0 }
        val already = seen.load()
        val now = mutableMapOf<String, String>()
        var raised = 0
        for (feed in feeds.filter { it.needsLogin }) {
            val key = feed.lastError.orEmpty()
            now[feed.source] = key
            if (already[feed.source] != key) {
                raise(LoginExpiry(feed.source, titleFor(feed.source), feed.sentence))
                raised++
            }
        }
        // Rewritten to exactly the sources still expired: that is what clears a recovered one.
        if (now != already) seen.save(now)
        return raised
    }

    companion object {
        /** A short label for the title only; the body is always the server's sentence. */
        fun titleFor(source: String): String = when (source) {
            "canvas" -> "Canvas login expired"
            "webassign" -> "WebAssign login expired"
            "drive_statements" -> "Google Drive login expired"
            "plaid" -> "Bank connection needs sign-in"
            else -> "$source login expired"
        }
    }
}
