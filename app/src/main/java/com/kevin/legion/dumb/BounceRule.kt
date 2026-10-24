package com.kevin.legion.dumb

/**
 * Everything the bounce decision reads, flattened so the rule is testable without Android (ADR 0062).
 * [hasLauncherEntry] is looked up last and only when the cheaper checks have not already decided.
 */
data class BounceContext(
    val modeOn: Boolean,
    val ownPackage: String,
    /**
     * [BlockModel]'s verdict, the one source: false for LEGION, Settings (it stays reachable, the
     * mode is friction and not a lock) and the phone's HOME app (bouncing it would loop: Home,
     * bounce, Home, bounce), for kept apps, and for anything not tagged a feed.
     */
    val isBlocked: (String) -> Boolean,
    /** A phone or VoIP call is in progress. */
    val inCall: Boolean,
    /** LEGION itself just opened a system picker (contacts, folder) and expects another app's window. */
    val graceActive: Boolean,
    /** A chosen person just messaged on this package and the allowance switch is on. */
    val allowedAfterChosenPerson: Boolean,
)

/**
 * Whether a window change should be bounced home (ADR 0062, amended 2026-10-24 from an allowlist to a
 * blocklist). Bounces ONLY a package that: (a) has a launcher activity, (b) [BounceContext.isBlocked]
 * says is blocked, (c) is not LEGION. A package with no launcher entry
 * (SystemUI, keyboards, the in-call screen, the permission controller, the package installer, the
 * share sheet) is never bounced, and nothing is bounced during a call or while Settings is the
 * window.
 */
object BounceRule {
    fun shouldBounce(packageName: String?, ctx: BounceContext, hasLauncherEntry: (String) -> Boolean): Boolean {
        val exempt = packageName.isNullOrBlank() ||
            !ctx.modeOn ||
            ctx.inCall ||
            ctx.graceActive ||
            packageName == ctx.ownPackage ||
            ctx.allowedAfterChosenPerson
        val pkg = packageName.orEmpty()
        return !exempt && ctx.isBlocked(pkg) && hasLauncherEntry(pkg)
    }
}

/**
 * Short-lived permissions that stop the bounce. Both are in memory: they are meant to lapse.
 *
 * - [armGrace] is for LEGION opening a system picker itself (the contacts picker, a folder picker),
 *   whose window belongs to another package that is not on the kept list.
 * - [allowPackage] is for **a chosen person's message arriving on an app** (so the user can open it
 *   and reply). It is OFF by default ([ALLOW_APP_AFTER_CHOSEN_PERSON]); see that constant.
 */
class BounceAllowance(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var graceUntil = 0L
    private val allowedUntil = HashMap<String, Long>()

    fun armGrace(forMs: Long = GRACE_MS) = synchronized(lock) {
        graceUntil = maxOf(graceUntil, clock() + forMs)
    }

    fun graceActive(): Boolean = synchronized(lock) { clock() < graceUntil }

    fun allowPackage(packageName: String, forMs: Long = PACKAGE_ALLOWANCE_MS) = synchronized(lock) {
        allowedUntil[packageName] = clock() + forMs
    }

    fun packageAllowed(packageName: String): Boolean = synchronized(lock) {
        val until = allowedUntil[packageName]
        when {
            until == null -> false
            clock() < until -> true
            else -> {
                allowedUntil.remove(packageName)
                false
            }
        }
    }

    companion object {
        const val GRACE_MS = 120_000L
        const val PACKAGE_ALLOWANCE_MS = 600_000L

        /**
         * **OPEN QUESTION for Kevin (ADR 0062 does not answer it).** When a chosen person messages on
         * a hidden app (WhatsApp, say), does tapping that notification OPEN the app, or bounce like
         * any other hidden app? The rulings say a notification tap bounces and also that chosen people
         * get through on any app; read literally the first wins and he can read Mia's message in the
         * shade but not open the chat. Flip this to true to open the app for [PACKAGE_ALLOWANCE_MS]
         * after each chosen-person message on it. Left false so the build does what the rulings say.
         */
        const val ALLOW_APP_AFTER_CHOSEN_PERSON = false
    }
}
