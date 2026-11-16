package com.kevin.legion.assistant.cards

import java.io.File
import org.json.JSONObject

/**
 * `shared/cards/fixtures.json`: one made-up card of each general kind with the words every screen
 * says for it, read by the engine's, the web's and the phone's tests. Found by walking up from the
 * test's working directory, as the Tetris fixtures are. Needs org.json, so a Robolectric test.
 */
internal object SharedCardFixtures {

    private fun file(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/cards/fixtures.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("shared/cards/fixtures.json not found above ${System.getProperty("user.dir")}")
    }

    /** Each entry: `card` (the wire shape) and `words`. A fresh copy per read: tests may mutate. */
    val all: List<JSONObject>
        get() {
            val cards = JSONObject(file().readText()).getJSONArray("cards")
            return (0 until cards.length()).map { cards.getJSONObject(it) }
        }

    /** The parsed card of the first fixture of [kind]. */
    fun card(kind: String, index: Int = 0): ConversationCard =
        ServerCards.parse(
            all.filter { it.getJSONObject("card").getString("card") == kind }[index].getJSONObject("card"),
        )!!
}
