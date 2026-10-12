package com.kevin.legion.games

/**
 * Pure: the filesystem path RetroArch is given for a SAF document.
 *
 * RetroArch takes a path, not a content URI (ticket 02, on-device), so the path is derived from the
 * tree document id. The id is `<volume>:<relative path>`: `primary` is the phone's own storage,
 * `/storage/emulated/0`; any other volume id is a card, mounted at `/storage/<volume>`. RetroArch
 * reads the file with its own storage permission, which is why LEGION needs no all-files access.
 * Returns null for an id with no volume part, since a guessed path would be launched as if known.
 */
object RomPaths {
    fun filesystemPath(documentId: String): String? {
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        val relative = documentId.substringAfter(':', missingDelimiterValue = "").trimStart('/')
        if (volume.isEmpty() || relative.isEmpty()) return null
        return if (volume == "primary") "/storage/emulated/0/$relative" else "/storage/$volume/$relative"
    }
}

/** Pure: the folder a tree document id names, as the user would say it ("/sdcard/ROMs", or "<card>/ROMs"). */
fun folderLabel(treeDocumentId: String): String {
    val volume = treeDocumentId.substringBefore(':', missingDelimiterValue = "")
    val relative = treeDocumentId.substringAfter(':', missingDelimiterValue = "").trim('/')
    return when {
        volume == "primary" -> if (relative.isEmpty()) "/sdcard" else "/sdcard/$relative"
        volume.isEmpty() -> treeDocumentId
        else -> if (relative.isEmpty()) volume else "$volume/$relative"
    }
}
