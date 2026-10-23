package com.kevin.legion.books

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positions

/** Where an EPUB is, in the words the reader's bottom bar and the Continue card use. */
data class EpubPlace(
    val progress: Double,
    val chapterLabel: String?,
    val positionLabel: String,
    val timeLeft: String?,
    val locatorJson: String,
)

/**
 * An opened EPUB plus the things the reader derives from it once: the page list ("Page 212 of 560"
 * is Readium's `positions`, one per ~1000 characters), and the flat contents list. Readium types stay
 * in here and the navigator host; the ViewModel deals in [EpubPlace] and [TocEntry].
 */
@OptIn(ExperimentalReadiumApi::class)
class EpubSession private constructor(
    val publication: Publication,
    private val positions: List<Locator>,
    private val tocLinks: List<Link>,
    val toc: List<TocEntry>,
    /** Chapter title by resource href, so any locator in a chapter names it. */
    private val chapterByHref: Map<String, String>,
) {
    fun linkAt(index: Int): Link? = tocLinks.getOrNull(index)

    fun describe(locator: Locator): EpubPlace {
        val progress = (locator.locations.totalProgression ?: 0.0).coerceIn(0.0, 1.0)
        val href = locator.href.toString()
        val chapter = locator.title?.takeIf { it.isNotBlank() } ?: chapterByHref[href.substringBefore('#')]
        val position = locator.locations.position
        val label = when {
            position != null && positions.isNotEmpty() -> "Page $position of ${positions.size}"
            chapter != null -> chapter
            else -> "Reading"
        }
        return EpubPlace(progress, chapter, label, timeLeftFor(locator, position), locator.toJSON().toString())
    }

    private fun timeLeftFor(locator: Locator, position: Int?): String? {
        if (position == null) return null
        val key = locator.href.toString().substringBefore('#')
        val remaining = positions.count {
            it.href.toString().substringBefore('#') == key && (it.locations.position ?: 0) > position
        }
        return timeLeftLabel(minutesLeftInChapter(remaining))
    }

    /**
     * Where to open: the saved locator when this book has the chapter file it names, else the position
     * nearest its total progression, flagged [StartPlace.near] so the reader says "near" and never
     * claims the exact spot (ticket 07: another device's locator may not apply to this layout).
     * No saved locator at all is the start of the book, not a guess.
     */
    fun startFor(locatorJson: String?, progress: Double): StartPlace {
        val hrefs = positions.map { it.href.toString().substringBefore('#') }.toSet()
        return when {
            locatorJson == null -> StartPlace(null, near = false)
            LocatorFit.fits(locatorJson, hrefs) -> StartPlace(locatorJson, near = false)
            else -> {
                val index = EpubStart.nearestIndex(positions.map { it.locations.totalProgression ?: 0.0 }, progress)
                StartPlace(index?.let { positions[it].toJSON().toString() }, near = true)
            }
        }
    }

    companion object {
        suspend fun of(publication: Publication): EpubSession {
            val positions = publication.positions()
            val links = mutableListOf<Link>()
            val entries = mutableListOf<TocEntry>()
            fun walk(list: List<Link>, depth: Int) {
                for (link in list) {
                    entries += TocEntry(link.title?.trim().orEmpty().ifEmpty { "Untitled" }, depth, links.size)
                    links += link
                    walk(link.children, depth + 1)
                }
            }
            walk(publication.tableOfContents, 0)
            val byHref = buildMap {
                for (link in links) {
                    val title = link.title?.trim().orEmpty()
                    val href = publication.locatorFromLink(link)?.href?.toString()?.substringBefore('#')
                    if (title.isNotEmpty() && href != null) putIfAbsent(href, title)
                }
            }
            return EpubSession(publication, positions, links, entries, byHref)
        }
    }
}
