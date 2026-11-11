package com.kevin.legion.service

import kotlin.random.Random

/**
 * The openWakeWord streaming pipeline, with the three models behind interfaces so the buffering is
 * unit tested without ONNX Runtime and the real models are checked against Python in a golden test.
 *
 * A line-for-line port of `openwakeword.utils.AudioFeatures._streaming_features` and
 * `openwakeword.Model.predict` (dscripka/openWakeWord 368c037, v0.6.0), for audio fed in 1280-sample
 * (80 ms) frames:
 * 1. Samples are int16 values as floats, **not** scaled to +-1 (the melspectrogram model expects
 *    the int16 range).
 * 2. Each frame, the mel model runs on the last 1280 + 480 samples, giving 8 rows of 32 bins; each
 *    value becomes `x / 10 + 2` (the Python default transform) and the rows join a mel buffer that
 *    starts as 76 rows of ones.
 * 3. The embedding model runs on the last 76 mel rows, giving one 96-value feature row. The feature
 *    buffer starts as the embeddings of 4 s of random noise in [-1000, 1000), as Python's does.
 * 4. The head runs on the last [OwwHead.inputFrames] feature rows and gives one score in 0..1.
 * 5. The first [WARMUP_PREDICTIONS] scores after a reset are 0 (Python zeroes them during
 *    initialisation).
 *
 * Anything fed that is not a whole frame is carried to the next call, so the phone's 3200-sample
 * reads give 2 or 3 scores each. Not thread safe: the caller feeds it from one capture loop, inside
 * the wake gate.
 */
class OwwStream(
    private val mel: OwwMel,
    private val embedding: OwwEmbedding,
    private val head: OwwHead,
    private val seed: Long = 0L,
) {
    private val raw = FloatArray(MEL_INPUT)
    private var rawFilled = 0
    private val pending = FloatArray(FRAME)
    private var pendingCount = 0

    private val melRows = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var predictions = 0

    init {
        reset()
    }

    /** Clears every buffer to the state a fresh Python `Model` starts in. */
    fun reset() {
        raw.fill(0f)
        rawFilled = 0
        pendingCount = 0
        melRows.clear()
        repeat(MEL_WINDOW) { melRows.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        initialFeatures().forEach { features.addLast(it) }
        predictions = 0
    }

    /**
     * Feeds [n] samples of [buf] and returns one score per completed 80 ms frame (possibly none),
     * oldest first.
     */
    fun feed(buf: ShortArray, n: Int): FloatArray {
        val out = ArrayList<Float>(n / FRAME + 1)
        var i = 0
        while (i < n) {
            val take = minOf(FRAME - pendingCount, n - i)
            for (k in 0 until take) pending[pendingCount + k] = buf[i + k].toFloat()
            pendingCount += take
            i += take
            if (pendingCount == FRAME) {
                out.add(frame(pending))
                pendingCount = 0
            }
        }
        return out.toFloatArray()
    }

    /** One whole frame: steps 2 to 5 above. */
    private fun frame(samples: FloatArray): Float {
        // Keep the last 1760 samples (this frame plus 480 before it) as the mel model's input.
        System.arraycopy(raw, FRAME, raw, 0, MEL_INPUT - FRAME)
        System.arraycopy(samples, 0, raw, MEL_INPUT - FRAME, FRAME)
        rawFilled = minOf(MEL_INPUT, rawFilled + FRAME)
        val input = if (rawFilled < MEL_INPUT) raw.copyOfRange(MEL_INPUT - rawFilled, MEL_INPUT) else raw
        for (row in mel.mel(input)) {
            melRows.addLast(FloatArray(row.size) { row[it] / MEL_SCALE + MEL_OFFSET })
        }
        while (melRows.size > MEL_MAX_ROWS) melRows.removeFirst()

        if (melRows.size >= MEL_WINDOW) {
            val window = Array(MEL_WINDOW) { melRows[melRows.size - MEL_WINDOW + it] }
            features.addLast(embedding.embed(window))
            while (features.size > FEATURE_MAX_ROWS) features.removeFirst()
        }

        val need = head.inputFrames
        val score = if (features.size >= need) {
            head.score(Array(need) { features[features.size - need + it] })
        } else {
            0f
        }
        predictions++
        return if (predictions <= WARMUP_PREDICTIONS) 0f else score
    }

    /** Python: `_get_embeddings(np.random.randint(-1000, 1000, 16000 * 4))`, the whole clip at once. */
    private fun initialFeatures(): List<FloatArray> {
        val rnd = Random(seed)
        val noise = FloatArray(SAMPLE_RATE * INIT_SECONDS) { (rnd.nextInt(-INIT_NOISE, INIT_NOISE)).toFloat() }
        val rows = mel.mel(noise).map { r -> FloatArray(r.size) { r[it] / MEL_SCALE + MEL_OFFSET } }
        val out = ArrayList<FloatArray>()
        var start = 0
        while (start + MEL_WINDOW <= rows.size) {
            out.add(embedding.embed(Array(MEL_WINDOW) { rows[start + it] }))
            start += MEL_STEP
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME = 1280
        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val MEL_STEP = 8
        const val EMBEDDING_DIM = 96
        const val WARMUP_PREDICTIONS = 5
        private const val MEL_CONTEXT = 480
        private const val MEL_INPUT = FRAME + MEL_CONTEXT
        private const val MEL_MAX_ROWS = 970
        private const val FEATURE_MAX_ROWS = 120
        private const val MEL_SCALE = 10f
        private const val MEL_OFFSET = 2f
        private const val INIT_SECONDS = 4
        private const val INIT_NOISE = 1000
    }
}

/** int16-range samples to mel rows (frames x 32), before the `x / 10 + 2` transform. */
fun interface OwwMel {
    fun mel(samples: FloatArray): Array<FloatArray>
}

/** 76 transformed mel rows to one 96-value feature row. */
fun interface OwwEmbedding {
    fun embed(window: Array<FloatArray>): FloatArray
}

/** The trained wake phrase head: the last [inputFrames] feature rows to a score in 0..1. */
interface OwwHead {
    val inputFrames: Int
    fun score(features: Array<FloatArray>): Float
}
