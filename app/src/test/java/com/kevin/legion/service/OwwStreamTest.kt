package com.kevin.legion.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OwwStream's buffering with fake models, so every rule of the Python port is checked without ONNX
 * Runtime: frame size and carry, the mel model's input length, the `x / 10 + 2` transform, the
 * 76-row embedding window, the head's last-N rows, the five zeroed start-up scores and reset.
 */
class OwwStreamTest {

    /** Mel rows per call as the real model gives them: (samples - 512) / 160 + 1, 32 bins of [value]. */
    private class FakeMel(private val value: Float = 0f) : OwwMel {
        val inputSizes = mutableListOf<Int>()
        override fun mel(samples: FloatArray): Array<FloatArray> {
            inputSizes += samples.size
            val rows = (samples.size - 512) / 160 + 1
            return Array(rows) { FloatArray(OwwStream.MEL_BINS) { value } }
        }
    }

    /** Feature row = [first transformed mel value of the window's newest row, call index]. */
    private class FakeEmbedding : OwwEmbedding {
        val windows = mutableListOf<Array<FloatArray>>()
        override fun embed(window: Array<FloatArray>): FloatArray {
            windows += window
            return FloatArray(OwwStream.EMBEDDING_DIM).also {
                it[0] = window.last()[0]
                it[1] = windows.size.toFloat()
            }
        }
    }

    private class FakeHead(override val inputFrames: Int = 16, private val score: Float = 0.9f) : OwwHead {
        val inputs = mutableListOf<Array<FloatArray>>()
        override fun score(features: Array<FloatArray>): Float {
            inputs += features
            return score
        }
    }

    private fun silence(n: Int) = ShortArray(n)

    @Test
    fun `one score per whole 1280-sample frame, the rest carried to the next read`() {
        val stream = OwwStream(FakeMel(), FakeEmbedding(), FakeHead())
        // The phone reads 3200 samples: 2.5 frames, so the scores alternate 2, 3, 2, 3.
        assertEquals(listOf(2, 3, 2, 3), (1..4).map { stream.feed(silence(3200), 3200).size })
        assertEquals(0, stream.feed(silence(1279), 1279).size)
        assertEquals(1, stream.feed(silence(1), 1).size)
    }

    @Test
    fun `only n samples of the buffer are read`() {
        val stream = OwwStream(FakeMel(), FakeEmbedding(), FakeHead())
        assertEquals(1, stream.feed(ShortArray(4000), 1280).size)
    }

    @Test
    fun `mel input is the frame plus 480 samples before it, once there are any`() {
        val mel = FakeMel()
        val stream = OwwStream(mel, FakeEmbedding(), FakeHead())
        mel.inputSizes.clear() // the start-up noise call
        stream.feed(silence(1280 * 3), 1280 * 3)
        assertEquals(listOf(1280, 1760, 1760), mel.inputSizes)
    }

    @Test
    fun `the mel model sees int16-range samples, not scaled to plus or minus one`() {
        var seen = 0f
        val mel = OwwMel { s -> seen = s.last(); Array(8) { FloatArray(OwwStream.MEL_BINS) } }
        val stream = OwwStream(mel, FakeEmbedding(), FakeHead())
        stream.feed(ShortArray(1280) { 12_345 }, 1280)
        assertEquals(12_345f, seen, 0f)
    }

    @Test
    fun `mel rows are transformed x over 10 plus 2 and the window is 76 rows, newest last`() {
        val embedding = FakeEmbedding()
        val stream = OwwStream(FakeMel(value = 30f), embedding, FakeHead())
        embedding.windows.clear()
        stream.feed(silence(1280 * 2), 1280 * 2)
        val w = embedding.windows.last()
        assertEquals(76, w.size)
        assertEquals(30f / 10f + 2f, w.last()[0], 1e-6f)
        // Frames 1 and 2 add 5 + 8 = 13 real rows; the 63 before them are the starting ones.
        assertEquals(1f, w[76 - 14][0], 0f)
        assertEquals(5f, w[76 - 13][0], 1e-6f)
    }

    @Test
    fun `start-up feature rows are the embeddings of 4 s of noise, 41 windows of 76 mel rows`() {
        val embedding = FakeEmbedding()
        OwwStream(FakeMel(), embedding, FakeHead())
        // 64000 samples -> 397 mel rows -> windows at 0, 8, ..., 320.
        assertEquals(41, embedding.windows.size)
    }

    @Test
    fun `the head gets the newest inputFrames feature rows, oldest first`() {
        val embedding = FakeEmbedding()
        val head = FakeHead(inputFrames = 16)
        val stream = OwwStream(FakeMel(), embedding, head)
        stream.feed(silence(1280 * 3), 1280 * 3)
        val rows = head.inputs.last()
        assertEquals(16, rows.size)
        val callIndex = rows.map { it[1].toInt() }
        assertEquals((41 + 3 - 15..41 + 3).toList(), callIndex)
    }

    @Test
    fun `the first five scores after a start or reset are zero, as in Python`() {
        val stream = OwwStream(FakeMel(), FakeEmbedding(), FakeHead(score = 0.9f))
        val first = stream.feed(silence(1280 * 7), 1280 * 7)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.9f, 0.9f), first, 0f)
        stream.reset()
        val again = stream.feed(silence(1280 * 6), 1280 * 6)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.9f), again, 0f)
    }

    @Test
    fun `reset drops a carried partial frame`() {
        val stream = OwwStream(FakeMel(), FakeEmbedding(), FakeHead())
        stream.feed(silence(1000), 1000)
        stream.reset()
        assertEquals(0, stream.feed(silence(1000), 1000).size)
        assertEquals(1, stream.feed(silence(280), 280).size)
    }

    @Test
    fun `a long stream keeps the same window shapes`() {
        val embedding = FakeEmbedding()
        val head = FakeHead()
        val stream = OwwStream(FakeMel(), embedding, head)
        repeat(500) { stream.feed(silence(3200), 3200) }
        assertEquals(76, embedding.windows.last().size)
        assertTrue(head.inputs.last().size == 16)
    }
}
