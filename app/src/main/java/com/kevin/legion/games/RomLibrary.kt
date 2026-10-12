package com.kevin.legion.games

/** One child of a folder in the granted tree. */
data class TreeChild(val documentId: String, val name: String, val isDirectory: Boolean)

/** The storage seam: list a folder's children. SAF in production, a map in tests. */
fun interface RomTree {
    fun children(documentId: String): List<TreeChild>
}

/**
 * What a scan found. [systemFoldersSeen] tells "no system folders here" from "folders, but empty";
 * [unreadable] means the root itself could not be listed, which is not the same as empty.
 */
data class ScanResult(val games: List<Game>, val systemFoldersSeen: Int, val unreadable: Boolean = false)

/**
 * Walks the granted root: each child folder named for a system (gb, gba, psx...) is scanned, down
 * to [MAX_DEPTH] levels so a game kept in its own folder is still found. Folders that are not a
 * system name are ignored. A folder that cannot be listed is skipped, not fatal: the games already
 * found are still real.
 */
class RomLibrary(private val tree: RomTree) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a provider can throw anything
    fun scan(rootDocumentId: String): ScanResult {
        val rootChildren = try {
            tree.children(rootDocumentId)
        } catch (e: Exception) {
            return ScanResult(emptyList(), 0, unreadable = true)
        }
        val systemFolders = rootChildren.filter { it.isDirectory }
            .mapNotNull { folder -> GameSystem.fromFolder(folder.name)?.let { it to folder } }
        val games = systemFolders.flatMap { (system, folder) ->
            RomScan.games(system, filesUnder(folder.documentId, depth = 1))
        }
        return ScanResult(games, systemFolders.size)
    }

    private fun filesUnder(folderId: String, depth: Int): List<RomEntry> =
        tree.safeChildren(folderId).flatMap { child ->
            when {
                !child.isDirectory -> listOf(RomEntry(child.documentId, child.name))
                depth < MAX_DEPTH -> filesUnder(child.documentId, depth + 1)
                else -> emptyList()
            }
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // one bad folder must not hide the rest
    private fun RomTree.safeChildren(id: String): List<TreeChild> =
        try {
            children(id)
        } catch (e: Exception) {
            emptyList()
        }

    companion object {
        const val MAX_DEPTH = 2
    }
}
