package com.kevin.midnightai.navigation

/** What a read of the handed-over map token found (review C-1). */
sealed interface TokenRead {
    data class Present(val token: String) : TokenRead
    /** No committed pairing, or it carries no map token: a real absence (Forget). */
    data object Absent : TokenRead
    /** A committed pairing exists but its bundle could not be read right now (a Keystore hiccup). */
    data object Unreadable : TokenRead
}

/**
 * What to do with the navigator after a token read (review C-1): a failed read changes nothing (a
 * trip keeps running on the token already in use); only a real absence or a different token tears
 * the navigator down.
 */
enum class NavTokenDecision {
    KEEP, SET, DROP;

    companion object {
        fun of(before: String?, read: TokenRead): NavTokenDecision = when (read) {
            TokenRead.Unreadable -> KEEP
            TokenRead.Absent -> if (before != null) DROP else SET
            is TokenRead.Present -> if (before != null && before != read.token) DROP else SET
        }
    }
}
