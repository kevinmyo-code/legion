package com.kevin.midnightai.link

import android.content.Context
import com.kevin.midnightai.service.DeviceRole
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where this unit stands with its phone (spec 3.7):
 * - NOT_PAIRED: no pairing record at all.
 * - PAIRED: a committed link key that decrypts, nothing pending.
 * - PENDING: a new pairing waits for the phone's first word (with or without a committed one).
 * - BROKEN: a record exists but its key cannot be read (Keystore failure, restore, corruption):
 *   accept no phone position at all, never say "Paired", never label a fix "Phone".
 */
enum class LinkStatus { NOT_PAIRED, PAIRED, PENDING, BROKEN }

/** The link keys as read (decrypted) right now. Never logged; [toString] says nothing. */
class LinkKeys(val committed: ByteArray?, val pending: ByteArray?, val status: LinkStatus) {
    override fun toString(): String = "LinkKeys($status)"
}

/** How the location receiver runs (one decision, used by every start site). */
enum class FeedMode {
    /** Not listening (role unset and never paired, or this device is the beacon itself). */
    OFF,
    /** Unpaired unit, beacon role set by hand: the legacy unauthenticated feed, labelled unverified. */
    MNAI1,
    /** Paired (or pending): authenticated MNAI2 only. */
    MNAI2,
    /** A pairing record whose key cannot be read: listen to nothing at all. */
    NOTHING,
}

object CarLinkRules {
    /**
     * Review H1/H2 (2026-11-20). MNAI1 is gated on "no pairing record exists", never on whether a
     * key happened to decrypt: a paired unit with an unreadable key accepts nothing.
     */
    fun feedMode(role: DeviceRole.Role, status: LinkStatus): FeedMode = when {
        role == DeviceRole.Role.GPS_BEACON -> FeedMode.OFF
        status == LinkStatus.BROKEN -> FeedMode.NOTHING
        status == LinkStatus.PAIRED || status == LinkStatus.PENDING -> FeedMode.MNAI2
        role == DeviceRole.Role.HEAD_UNIT -> FeedMode.MNAI1
        else -> FeedMode.OFF
    }

    /** The Settings line for each state. */
    fun statusLine(status: LinkStatus, phoneName: String?): String = when (status) {
        LinkStatus.NOT_PAIRED -> "Pair once with your phone: it sends location and your sign-in"
        LinkStatus.PAIRED -> "Paired with ${phoneName ?: "your phone"}: location comes from it"
        LinkStatus.PENDING -> "Paired, waiting for the phone"
        LinkStatus.BROKEN -> "Pairing broken: pair again"
    }
}

/** The car link's storage (Android: [LinkKeyStore]); a seam so the feed and pairing are testable. */
interface LinkKeySource {
    /** Cheap, no decryption: is there any pairing record (committed or pending)? */
    fun hasRecord(): Boolean

    /** Decrypts (Keystore): call off the main thread. */
    fun keys(): LinkKeys

    /** PENDING -> PAIRED (spec 3.7). Null on success, else a reason in words. */
    fun commitPending(): String?

    /** Drops PENDING; COMMITTED is untouched. */
    fun dropPending()

    /** Forget: the link keys and exactly the values that came over the link (spec 3.7). */
    fun forget()

    fun phoneName(): String?
}

private object NoLink : LinkKeySource {
    override fun hasRecord() = false
    override fun keys() = LinkKeys(null, null, LinkStatus.NOT_PAIRED)
    override fun commitPending() = "not paired"
    override fun dropPending() = Unit
    override fun forget() = Unit
    override fun phoneName(): String? = null
}

/**
 * Process-wide car link state. Every change (pair, pending, commit, drop, forget, revoke) goes
 * through [changed], which re-reads the status and tells the listeners (the location receiver
 * rebuilds its feed at once, review H2). Nothing here is ever logged.
 */
object CarLink {
    @Volatile
    var store: LinkKeySource = NoLink

    /**
     * Open pairing sessions, one token each (review P5: a single flag let an old session's late
     * close switch off the new session's protection).
     */
    private val openSessions: MutableSet<Any> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** True while any pairing session is open (review N1, spec 3.7). */
    val pairingSessionOpen: Boolean get() = openSessions.isNotEmpty()

    fun pairingSessionStarted(token: Any) {
        openSessions += token
    }

    /** Idempotent: ending a session twice, or one that never started, changes nothing. */
    fun pairingSessionEnded(token: Any) {
        openSessions -= token
    }

    private val _status = MutableStateFlow(LinkStatus.NOT_PAIRED)

    /** Last status read by [refresh]; NOT_PAIRED until the first read. */
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(Context) -> Unit>()

    fun addListener(listener: (Context) -> Unit) {
        listeners += listener
    }

    /** Re-reads the keys (Keystore: off the main thread) and returns them. */
    fun refresh(): LinkKeys {
        val keys = runCatching { store.keys() }.getOrElse {
            LinkKeys(null, null, if (store.hasRecord()) LinkStatus.BROKEN else LinkStatus.NOT_PAIRED)
        }
        _status.value = keys.status
        return keys
    }

    /** Something about the pairing changed: refresh and rebuild whatever depends on it. */
    fun changed(context: Context) {
        refresh()
        listeners.forEach { runCatching { it(context.applicationContext) } }
    }

    internal fun resetForTest() {
        store = NoLink
        _status.value = LinkStatus.NOT_PAIRED
        listeners.clear()
        openSessions.clear()
    }
}
