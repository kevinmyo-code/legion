package com.kevin.legion.dumb

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat

/**
 * What the setup screen needs to know about each one-time grant (ADR 0062). A snapshot of facts,
 * not Android calls, so the wording is testable. Each later slice adds its own field; a grant that
 * has no feature behind it yet is not listed.
 */
data class DumbGrantFacts(
    val notificationAccess: Boolean,
    val dndAccess: Boolean,
    val callScreeningRole: Boolean,
    val accessibilityEnabled: Boolean = true,
    /** Call screening needs Android 10 (API 29) for the role; below it the row says so and has no button. */
    val callScreeningSupported: Boolean = true,
)

/** What a grant row's button does. */
sealed interface GrantAction {
    /** Opens a system settings page by intent action. */
    data class OpenSettings(val intentAction: String) : GrantAction

    /** Asks for a role through the system's own dialog. */
    data class RequestRole(val role: String) : GrantAction

    /** Opens LEGION's own App info page, where "Allow restricted settings" lives for a sideloaded build. */
    data object OpenAppInfo : GrantAction

    /** No button: the grant cannot be made from a screen. */
    data object None : GrantAction
}

/** One line on the setup screen: what it is for, its state in words, and what the button does. */
data class DumbGrantRow(
    val id: Id,
    val title: String,
    val state: String,
    val granted: Boolean,
    val buttonLabel: String,
    val action: GrantAction,
    /** Said when this grant is missing and dumb mode is on: what is NOT happening. */
    val missingEffect: String,
) {
    enum class Id { NOTIFICATION_ACCESS, DND_ACCESS, CALL_SCREENING, ACCESSIBILITY, RESTRICTED_SETTINGS }
}

object DumbSetup {
    fun rows(facts: DumbGrantFacts): List<DumbGrantRow> = listOf(
        DumbGrantRow(
            id = DumbGrantRow.Id.NOTIFICATION_ACCESS,
            title = "Notification access",
            state = if (facts.notificationAccess) {
                "Granted. LEGION can hold other apps' notifications."
            } else {
                "Not granted. LEGION cannot hold other apps' notifications."
            },
            granted = facts.notificationAccess,
            buttonLabel = if (facts.notificationAccess) "Open settings" else "Grant access",
            action = GrantAction.OpenSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            missingEffect = "Notifications are not being held.",
        ),
        DumbGrantRow(
            id = DumbGrantRow.Id.DND_ACCESS,
            title = "Do Not Disturb access",
            state = if (facts.dndAccess) {
                "Granted. LEGION can silence the phone while the mode is on and put it back after."
            } else {
                "Not granted. The phone's sounds are left as they are."
            },
            granted = facts.dndAccess,
            buttonLabel = if (facts.dndAccess) "Open settings" else "Grant access",
            action = GrantAction.OpenSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
            missingEffect = "The phone's sounds are not being silenced.",
        ),
        callScreeningRow(facts),
        accessibilityRow(facts),
    ) + restrictedSettingsRow(facts)


    private fun accessibilityRow(facts: DumbGrantFacts) = DumbGrantRow(
        id = DumbGrantRow.Id.ACCESSIBILITY,
        title = "Accessibility service",
        state = if (facts.accessibilityEnabled) {
            "On. A hidden app that opens is sent back home."
        } else {
            "Off. Hidden apps opened another way are not sent home. Turn on \"Dumb mode\" under Downloaded apps."
        },
        granted = facts.accessibilityEnabled,
        buttonLabel = "Open accessibility",
        action = GrantAction.OpenSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        missingEffect = "Hidden apps are not sent home.",
    )

    /**
     * Shown only while the accessibility service is off. A sideloaded build cannot be switched on
     * until "Allow restricted settings" is ticked in App info (its menu), which Android hides until a
     * first attempt is refused. The user can go there first.
     */
    private fun restrictedSettingsRow(facts: DumbGrantFacts): List<DumbGrantRow> =
        if (facts.accessibilityEnabled) {
            emptyList()
        } else {
            listOf(
                DumbGrantRow(
                    id = DumbGrantRow.Id.RESTRICTED_SETTINGS,
                    title = "Allow restricted settings",
                    state = "If the accessibility switch is greyed out: open App info, tap the three dots at the " +
                        "top right, then Allow restricted settings. Needed once for a sideloaded app.",
                    granted = true,
                    buttonLabel = "Open App info",
                    action = GrantAction.OpenAppInfo,
                    missingEffect = "",
                ),
            )
        }

    private fun callScreeningRow(facts: DumbGrantFacts) = DumbGrantRow(
        id = DumbGrantRow.Id.CALL_SCREENING,
        title = "Call screening",
        state = when {
            !facts.callScreeningSupported -> "Not available. Call screening needs Android 10 or later."
            facts.callScreeningRole ->
                "Granted. Calls from anyone you did not choose are silent, and ring on a second call."
            else ->
                "Not granted. Every call rings. Granting this replaces the phone's current call screener."
        },
        granted = facts.callScreeningRole && facts.callScreeningSupported,
        buttonLabel = if (facts.callScreeningRole) "Open settings" else "Grant role",
        action = when {
            !facts.callScreeningSupported -> GrantAction.None
            facts.callScreeningRole -> GrantAction.OpenSettings(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            else -> GrantAction.RequestRole(ROLE_CALL_SCREENING)
        },
        missingEffect = "Strangers' calls still ring.",
    )

    /** The bounce service's class name, as the system lists it. */
    const val BOUNCE_SERVICE = "com.kevin.legion.dumb.DumbBounceService"

    /** `RoleManager.ROLE_CALL_SCREENING`, spelled out so this file's row logic stays plain Kotlin. */
    const val ROLE_CALL_SCREENING = "android.app.role.CALL_SCREENING"

    /** "Not active: ..." lines for every missing grant, or null when nothing is missing. */
    fun notActive(rows: List<DumbGrantRow>): String? =
        rows.filter { !it.granted }.takeIf { it.isNotEmpty() }?.joinToString(" ") { it.missingEffect }
            ?.let { "Not active: $it" }

    fun factsFor(context: Context): DumbGrantFacts = DumbGrantFacts(
        notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName),
        dndAccess = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted,
        callScreeningRole = callScreeningHeld(context),
        accessibilityEnabled = accessibilityOn(context),
        callScreeningSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    )

    private fun accessibilityOn(context: Context): Boolean = runCatching {
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo.serviceInfo
                service.packageName == context.packageName && service.name == BOUNCE_SERVICE
            }
    }.getOrDefault(false)

    private fun callScreeningHeld(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(ROLE_CALL_SCREENING) == true

    /** The role-request intent for [role], or null below Android 10 or when the system will not offer it. */
    fun roleRequestIntent(context: Context, role: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.takeIf { it.isRoleAvailable(role) }
                ?.createRequestRoleIntent(role)
        } else {
            null
        }
}
