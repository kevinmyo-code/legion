package com.kevin.midnightai.ai

/** Where the unit's Gemini key came from (Kevin, 2026-11-20: the phone's own key, over the car link). */
enum class GeminiKeySource { CAR_LINK, TYPED, NONE }

/** The key in use and its source. Never logged; [toString] says only the source. */
data class GeminiKeyChoice(val key: String, val source: GeminiKeySource) {
    override fun toString(): String = "GeminiKeyChoice($source)"

    companion object {
        /** The handed-over key wins over one typed on this unit; a typed key is the fallback. */
        fun resolve(link: String?, typed: String?): GeminiKeyChoice = when {
            !link.isNullOrBlank() -> GeminiKeyChoice(link.trim(), GeminiKeySource.CAR_LINK)
            !typed.isNullOrBlank() -> GeminiKeyChoice(typed.trim(), GeminiKeySource.TYPED)
            else -> GeminiKeyChoice("", GeminiKeySource.NONE)
        }

        /** Settings words for each source: where the key came from, never the key. */
        fun words(source: GeminiKeySource, builtIn: Boolean): String = when (source) {
            GeminiKeySource.CAR_LINK ->
                "Using your phone's Gemini key, sent over the car link. A key typed here is kept and used again if the phone is forgotten."
            GeminiKeySource.TYPED -> "Using the key typed on this unit."
            GeminiKeySource.NONE ->
                if (builtIn) "Using the app's built-in key." else "No Gemini key yet. Pair your phone, or paste a key here."
        }
    }
}
