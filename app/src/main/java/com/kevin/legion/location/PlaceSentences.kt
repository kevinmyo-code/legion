package com.kevin.legion.location

import com.kevin.legion.data.local.TaggedPlace

/**
 * Every sentence the saved-places flows speak or show, in one place so the voice tool and the
 * Saved places screen say the same thing about the same outcome (ADR 0035: one controller, not
 * two). Pure; no Android types beyond the entity.
 *
 * **Each one says what was saved, in the words that were saved.** A save by address reads the
 * geocoder's resolved address back rather than echoing what was asked for, so the model repeats
 * what is actually stored (Kevin, 2026-10-09). A refusal starts "Nothing was saved" or names what
 * did not happen, never an outcome verb over nothing (CLAUDE.md section 7).
 */
@Suppress("TooManyFunctions") // a catalog of sentences, one per outcome; splitting it would split the wording
internal object PlaceSentences {
    fun display(label: String): String = if (label == "home" || label == "work") label else "\"$label\""

    private fun where(place: TaggedPlace): String =
        place.address?.let { "at $it" } ?: "(no address on file, only its coordinates)"

    const val NO_LABEL = "I didn't catch what to call this spot - try something like 'home' or 'work'."

    const val NO_FIX = "I don't have a GPS lock yet, so I can't pin this spot. Give it a sec and try again."

    fun savedAtAddress(label: String, address: String, replaced: TaggedPlace?): String =
        "Saved ${display(label)} at $address." + replacedTail(replaced)

    fun savedHere(label: String, address: String?, lookupFailure: String?, replaced: TaggedPlace?): String {
        val head = if (address != null) {
            "Saved ${display(label)} where you are now: $address."
        } else {
            "Saved ${display(label)} where you are now, by its coordinates only. Its address is unknown" +
                (lookupFailure?.let { " - $it" } ?: " - the address lookup found nothing there") + "."
        }
        return head + replacedTail(replaced)
    }

    private fun replacedTail(replaced: TaggedPlace?): String =
        replaced?.let { " That replaces where ${display(it.label)} was before, ${where(it)}." }.orEmpty()

    fun confirmReplace(label: String, existing: TaggedPlace, newAddress: String?): String =
        "Nothing was saved yet. There is already a saved place called ${display(label)}, ${where(existing)}. " +
            "Saving would move it to ${newAddress ?: "where you are now"} and the old spot would be lost. " +
            "Ask the user to confirm; only after they say yes, save again with confirmed=true. " +
            "If they wanted a second place, use a different name; to change only the name, use rename_place."

    fun lookupUnavailable(why: String): String = "Nothing was saved. I couldn't look up that address: $why."

    fun lookupNotFound(query: String): String =
        "Nothing was saved. I couldn't find an address matching \"$query\". " +
            "Try the full street address with the city."

    fun severalMatches(candidates: List<GeocodedAddress>): String =
        "Nothing was saved. That address matches more than one place: " +
            candidates.mapIndexed { i, c -> "${i + 1}) ${c.address}" }.joinToString("; ") +
            ". Ask the user which one, then save again with that full address."

    fun confirmForget(existing: TaggedPlace): String =
        "Nothing was deleted yet. Forgetting ${display(existing.label)} would delete it, ${where(existing)}. " +
            "Ask the user to confirm; only after they say yes, call forget_place again with confirmed=true. " +
            "If they wanted to change its name, use rename_place instead."

    fun noSuchPlace(label: String): String = "I don't have a saved place called \"$label\"."

    fun forgot(label: String): String = "Forgot ${display(label)}. It is off the saved places."

    const val RENAME_NO_FROM = "I'm not sure which place you mean. Nothing was renamed."

    const val RENAME_NO_TO =
        "I didn't catch the new name - try something like 'home' or 'the gym'. Nothing was renamed."

    fun renameSame(label: String): String = "That place is already called ${display(label)}. Nothing was renamed."

    fun renameTaken(to: String): String =
        "Nothing was renamed. There is already a saved place called ${display(to)}. Rename or forget that one first."

    fun renamed(from: String, place: TaggedPlace, remindersMoved: Int, remindersFailed: Int): String {
        val moved = when (remindersMoved) {
            0 -> ""
            1 -> " 1 reminder moved with it."
            else -> " $remindersMoved reminders moved with it."
        }
        val failed = if (remindersFailed > 0) {
            " $remindersFailed reminder${if (remindersFailed == 1) "" else "s"} could not be moved and still " +
                "point at ${display(from)}."
        } else {
            ""
        }
        return "Renamed ${display(from)} to ${display(place.label)}. Same spot, ${where(place)}.$moved$failed"
    }

    const val RENAME_FAILED =
        "Something went wrong renaming that place - nothing was changed. Try again in a sec."
}
