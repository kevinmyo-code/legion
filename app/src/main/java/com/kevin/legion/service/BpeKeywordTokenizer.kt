package com.kevin.legion.service

/**
 * Turns a phrase into the BPE token line sherpa-onnx's keyword spotter wants
 * (`▁HE Y ▁S I RI`), on the phone.
 *
 * **Why this exists.** sherpa-onnx's own tool for this (`text2token.py`) is Python plus the
 * `sentencepiece` package and cannot run on a phone, and the wake phrase is "hey <companion name>"
 * with a name Kevin picks at runtime (ticket 09: never hardcoded). The Kotlin API only accepts the
 * already-tokenised form. So this reads the model's own `bpe.model` (a SentencePiece
 * `ModelProto`) and segments the phrase with its piece scores directly.
 *
 * Only the protobuf fields needed are decoded: `ModelProto.pieces` (field 1), each with
 * `piece` (1, string), `score` (2, float) and `type` (3, varint; 1 = NORMAL, the only kind used).
 * Encoding: the word-start marker goes in front of each word and the highest-total-score
 * segmentation into vocabulary pieces wins. (First written as greedy pair merging, which is
 * textbook BPE; the reference test showed this model's piece table does not support that, so it is
 * a best-path search. The file is still called bpe.model.)
 *
 * **A character the vocabulary lacks is an error, not a skip.** Silently dropping it would build a
 * keyword for a phrase other than the one asked for - the quiet-wrong failure this map keeps
 * finding. [tokensOf] returns null and the caller refuses in words.
 *
 * Correctness is pinned by `BpeKeywordTokenizerTest` against the model's own shipped
 * `keywords_raw.txt` / `keywords.txt` pairs, produced by the real SentencePiece.
 */
// Protobuf wire-format constants; early nulls are the refuse-in-words contract.
@Suppress("MagicNumber", "ReturnCount", "LoopWithTooManyJumpStatements") // see the line above
class BpeKeywordTokenizer private constructor(private val scores: Map<String, Float>) {

    /** Marker-prefixed BPE tokens for [phrase] (uppercased: the model's vocabulary is), or null. */
    fun tokensOf(phrase: String): List<String>? {
        val words = phrase.trim().uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val out = mutableListOf<String>()
        for (word in words) {
            val text = MARKER + word
            // Best-scoring segmentation (Viterbi). Found necessary by the reference test: this
            // model's "bpe.model" keeps whole-word pieces such as the marker plus WORLD with no
            // chain of smaller merges leading to them, so a greedy merge loop cannot reproduce
            // SentencePiece's output and a best-path search does.
            val best = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
            val from = IntArray(text.length + 1) { -1 }
            best[0] = 0.0
            for (end in 1..text.length) {
                for (start in maxOf(0, end - MAX_PIECE_CHARS) until end) {
                    if (best[start] == Double.NEGATIVE_INFINITY) continue
                    val sc = scores[text.substring(start, end)] ?: continue
                    if (best[start] + sc > best[end]) {
                        best[end] = best[start] + sc
                        from[end] = start
                    }
                }
            }
            if (from[text.length] < 0) return null
            val pieces = ArrayDeque<String>()
            var e = text.length
            while (e > 0) {
                pieces.addFirst(text.substring(from[e], e))
                e = from[e]
            }
            out.addAll(pieces)
        }
        return out
    }

    /** The keyword-file line for [phrase], tagged `@name`, or null when it cannot be encoded. */
    fun tokenise(phrase: String): String? =
        tokensOf(phrase)?.let {
            it.joinToString(" ") + " @" + phrase.trim().lowercase().replace(Regex("\\s+"), "_")
        }

    companion object {
        private const val MARKER = "▁"
        private const val TYPE_NORMAL = 1
        private const val MAX_PIECE_CHARS = 16

        private class Reader(val b: ByteArray, var pos: Int) {
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (true) {
                    val x = b[pos++].toInt() and 0xFF
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
            }

            fun skip(wireType: Int) {
                when (wireType) {
                    0 -> varint()
                    1 -> pos += 8
                    5 -> pos += 4
                    // Length first, THEN advance: `pos += varint()` would read pos before varint moved it.
                    2 -> {
                        val len = varint().toInt()
                        pos += len
                    }
                    else -> error("unsupported protobuf wire type $wireType in SentencePiece model")
                }
            }
        }

        /** Parses a SentencePiece `.model` file's bytes. Throws on a malformed file. */
        fun fromModel(bytes: ByteArray): BpeKeywordTokenizer {
            val scores = HashMap<String, Float>()
            val r = Reader(bytes, 0)
            while (r.pos < bytes.size) {
                val tag = r.varint().toInt()
                if (tag == (1 shl 3) or 2) {
                    val len = r.varint().toInt()
                    parsePiece(bytes, r.pos, r.pos + len, scores)
                    r.pos += len
                } else {
                    r.skip(tag and 7)
                }
            }
            return BpeKeywordTokenizer(scores)
        }

        private fun parsePiece(b: ByteArray, start: Int, end: Int, into: MutableMap<String, Float>) {
            val r = Reader(b, start)
            var piece: String? = null
            var score = 0f
            var type = TYPE_NORMAL
            while (r.pos < end) {
                val tag = r.varint().toInt()
                when (tag) {
                    (1 shl 3) or 2 -> {
                        val l = r.varint().toInt()
                        piece = String(b, r.pos, l, Charsets.UTF_8)
                        r.pos += l
                    }
                    (2 shl 3) or 5 -> {
                        val bits = (b[r.pos].toInt() and 0xFF) or ((b[r.pos + 1].toInt() and 0xFF) shl 8) or
                            ((b[r.pos + 2].toInt() and 0xFF) shl 16) or ((b[r.pos + 3].toInt() and 0xFF) shl 24)
                        score = java.lang.Float.intBitsToFloat(bits)
                        r.pos += 4
                    }
                    (3 shl 3) -> type = r.varint().toInt()
                    else -> r.skip(tag and 7)
                }
            }
            if (piece != null && type == TYPE_NORMAL) into[piece] = score
        }
    }
}
