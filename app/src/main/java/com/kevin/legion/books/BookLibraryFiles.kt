package com.kevin.legion.books

// Writing a library book so a killed write can never pass for a finished book: the bytes go to
// "<name>.part", are read back and checked, and only then renamed to the final name. A leftover
// ".part" is a known partial and is swept at the start of the next run.

data class DocDigest(val size: Long, val sha256: String)

/** The few document operations the writer needs; the SAF folder implements them, tests fake them. */
interface DocOps {
    fun names(): List<String>

    fun create(name: String): Boolean

    /** Replaces the whole content of [name]. */
    fun write(name: String, bytes: ByteArray): Boolean

    /** Size and hash of what is actually stored, read back; null when it cannot be read. */
    fun digest(name: String): DocDigest?

    fun rename(from: String, to: String): Boolean

    fun delete(name: String): Boolean
}

class PartFileWriter(private val docs: DocOps) {
    fun status(fileName: String, size: Long, sha256: String): FileStatus {
        if (fileName !in docs.names()) return FileStatus.ABSENT
        val stored = docs.digest(fileName)
        return if (stored == DocDigest(size, sha256)) FileStatus.SAME else FileStatus.DIFFERENT
    }

    /** True only when [finalName] holds exactly [bytes]; on any failure no partial file is left. */
    fun write(finalName: String, bytes: ByteArray): Boolean {
        val part = finalName + PART
        if (part in docs.names()) docs.delete(part)
        val ok = docs.create(part) &&
            docs.write(part, bytes) &&
            docs.digest(part) == DocDigest(bytes.size.toLong(), LibrarySha.of(bytes)) &&
            docs.rename(part, finalName)
        if (!ok) docs.delete(part)
        return ok
    }

    fun sweep() {
        docs.names().filter { it.endsWith(PART) }.forEach(docs::delete)
    }

    private companion object {
        const val PART = ".part"
    }
}
