package com.kevin.legion.brief

import com.kevin.legion.sitrep.SitrepModule

/**
 * The engine's brief as plain text for the voice model (`get_sitrep`): its own words, sections in
 * order, nothing reworded. Unverified lines carry `[unverified]`; News sources are named by title,
 * never by URL, since a link read aloud is noise.
 */
internal object BriefVoiceText {
    private val MODULE_FOR_SECTION = mapOf("car" to SitrepModule.FLEET, "news" to SitrepModule.NEWS)

    fun render(view: BriefView.Showing, modules: Set<SitrepModule>?): String {
        val out = StringBuilder("BRIEF ").append(view.header).append('.')
        view.savedCopy?.let { out.append("\n").append(it) }
        view.brief.notes.forEach { out.append("\nNOTE ").append(it) }
        view.brief.sections
            .filter { modules == null || MODULE_FOR_SECTION[it.key]?.let { m -> m in modules } == true }
            .forEach { out.append("\n").append(section(it)) }
        return out.toString()
    }

    private fun section(section: BriefSection): String {
        val lines = section.lines.joinToString(" | ") { line ->
            val mark = if (line.unverified) " [unverified]" else ""
            val sources = if (line.links.isEmpty()) "" else " (sources: ${line.links.joinToString(", ") { it.title }})"
            line.text + mark + sources
        }
        val head = "${section.title.uppercase()} (${section.status})"
        val body = listOfNotNull(lines.ifBlank { null }, section.note).joinToString(" ")
        return if (body.isEmpty()) head else "$head $body"
    }
}
