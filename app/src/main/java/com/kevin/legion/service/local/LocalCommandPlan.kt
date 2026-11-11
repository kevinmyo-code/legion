package com.kevin.legion.service.local

/**
 * What a matched [LocalCommand] does, as plain data: the SAME tool call Alfred would have made
 * (ADR 0035: one controller per capability, so the local path can never drift from the voice
 * one), opening a screen, or ending the conversation. Pure, so it is tested without Android; the
 * runner turns a [LocalAction.Tool] into `LiveToolbox.dispatch` and a [LocalAction.Open] into the
 * app's deep link.
 */
object LocalCommandPlan {

    fun of(command: LocalCommand): LocalAction = when (command) {
        is LocalCommand.Timer -> LocalAction.Tool(
            "clock",
            mapOf("action" to "timer_start", "seconds" to command.seconds),
        )
        is LocalCommand.Alarm -> LocalAction.Tool(
            "clock",
            mapOf(
                "action" to "alarm_set",
                "time" to "%02d:%02d".format(command.hour, command.minute),
                "days" to "none",
            ),
        )
        is LocalCommand.Media -> LocalAction.Tool("control_music", mapOf("action" to MEDIA.getValue(command.action)))
        is LocalCommand.Volume -> LocalAction.Tool(
            "control_volume",
            buildMap {
                put("action", VOLUME.getValue(command.action))
                command.level?.let { put("level", it) }
            },
        )
        is LocalCommand.ClearTicked -> LocalAction.Tool(
            "manage_checklist",
            mapOf("action" to "clear_ticked", "list" to LISTS.getValue(command.list)),
        )
        is LocalCommand.OpenScreen -> LocalAction.Open(ROUTES.getValue(command.screen), command.screen)
        LocalCommand.Sleep -> LocalAction.Dismiss
    }

    private val MEDIA = mapOf(
        MediaAction.PAUSE to "pause", MediaAction.RESUME to "play",
        MediaAction.NEXT to "next", MediaAction.PREVIOUS to "previous",
    )
    private val VOLUME = mapOf(
        VolumeAction.UP to "up", VolumeAction.DOWN to "down", VolumeAction.MUTE to "mute",
        VolumeAction.UNMUTE to "unmute", VolumeAction.SET to "set",
    )
    private val LISTS = mapOf("groceries" to "Groceries", "todo" to "Todo")

    /** Screen keys to `LegionRoute` values (strings here so this file stays Android-free). */
    internal val ROUTES = mapOf(
        "settings" to "settings", "calendar" to "calendar", "checklists" to "checklists",
        "money" to "money", "fleet" to "fleet", "body" to "body", "news" to "news", "home" to "home",
        "apps" to "apps", "places" to "fleet/places", "bought" to "bought",
    )
}

sealed interface LocalAction {
    /** One call to the tool Alfred would have called, with its own arguments. */
    data class Tool(val name: String, val args: Map<String, Any>) : LocalAction

    /** Bring the app up at [route] (a `LegionRoute`); [screen] is said back. */
    data class Open(val route: String, val screen: String) : LocalAction

    /** "That's all": nothing to do but stop listening. */
    data object Dismiss : LocalAction
}
