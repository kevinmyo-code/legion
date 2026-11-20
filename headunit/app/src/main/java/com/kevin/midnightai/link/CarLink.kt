package com.kevin.midnightai.link

/**
 * Where the car link's long-term key comes from (HU-1 T3 pairing writes it; T4's location feed
 * reads it). A seam so the location feed is testable without a Keystore, and so T3's real,
 * Keystore-wrapped store plugs in without T4 knowing how the key is kept.
 */
fun interface LinkKeySource {
    /** The 32-byte link key, or null when this unit has not been paired (or was forgotten). */
    fun linkKey(): ByteArray?

    /** Cheap check with no decryption (the Settings row and service start ask it on the main thread). */
    fun isPaired(): Boolean = linkKey() != null
}

/** Process-wide car link state: the installed key source. Nothing here is ever logged. */
object CarLink {
    @Volatile
    var keys: LinkKeySource = LinkKeySource { null }

    fun isPaired(): Boolean = runCatching { keys.isPaired() }.getOrDefault(false)
}
