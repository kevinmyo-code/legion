package com.kevin.legion.service

import ai.onnxruntime.OrtEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The phone's openWakeWord pipeline (OwwStream on OwwOrtModels, JVM ONNX Runtime) against the Python
 * implementation on the same clip, from `tools/openwakeword/make_oww_golden.py`: the feature row of
 * every 80 ms frame, and the head's score per frame, fed both in Python's 1280-sample frames and in
 * the phone's 3200-sample reads.
 *
 * The three model files are committed (licence: app/src/main/assets/openwakeword/MODELS.md) and pinned
 * by app/models.sha256, so this test RUNS everywhere, CI included: a missing file or a model other than
 * the one the vectors were made with (sha256 in golden.json) is a failure, never a skip (until
 * 2026-11-20 the files were gitignored and every clean clone skipped it).
 */
class OwwStreamGoldenTest {
    private val dir = listOf(File("src/main/assets/openwakeword"), File("app/src/main/assets/openwakeword"))
        .firstOrNull { it.isDirectory }

    private fun model(name: String): File? = dir?.let { File(it, name) }?.takeIf { it.isFile }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/openwakeword/$name")) { "missing /openwakeword/$name" }
            .use { it.readBytes() }

    private val meta by lazy { JSONObject(String(resource("golden.json"))) }

    private val audio: ShortArray by lazy {
        val b = ByteBuffer.wrap(resource("golden.pcm")).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        ShortArray(b.remaining()) { b.get(it) }
    }

    private val pythonFeatures: FloatArray by lazy {
        val b = ByteBuffer.wrap(resource("golden_features.f32")).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(b.remaining()) { b.get(it) }
    }

    private fun sha256(f: File) =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private class Run(val scores: List<Float>, val features: List<FloatArray>)

    /** Streams the golden clip in [chunk]-sample reads; records every feature row made from the clip. */
    private fun stream(mel: File, embedding: File, head: File, chunk: Int): Run {
        val env = OrtEnvironment.getEnvironment()
        OwwOrtModels(env, mel.readBytes(), embedding.readBytes(), head.readBytes()).use { models ->
            val rows = ArrayList<FloatArray>()
            var recording = false
            val tap = OwwEmbedding { w -> models.embed(w).also { if (recording) rows += it } }
            val s = OwwStream(models, tap, models)
            recording = true
            val scores = ArrayList<Float>()
            var i = 0
            while (i < audio.size) {
                val n = minOf(chunk, audio.size - i)
                scores += s.feed(audio.copyOfRange(i, i + n), n).toList()
                i += n
            }
            return Run(scores, rows)
        }
    }

    private fun requireModels(): Triple<File, File, File> {
        val mel = model(OwwModelFiles.MEL)
        val emb = model(OwwModelFiles.EMBEDDING)
        val head = model("hey_alfred.onnx")
        assertNotNull("melspectrogram.onnx missing from src/main/assets/openwakeword (it is committed)", mel)
        assertNotNull("embedding_model.onnx missing from src/main/assets/openwakeword (it is committed)", emb)
        assertNotNull("hey_alfred.onnx missing from src/main/assets/openwakeword (it is committed)", head)
        val m = mel!!
        val e = emb!!
        assertEquals(
            "melspectrogram.onnx is not the one the vectors were made with",
            meta.getString("mel_sha256"),
            sha256(m),
        )
        assertEquals(
            "embedding_model.onnx is not the one the vectors were made with",
            meta.getString("embedding_sha256"),
            sha256(e),
        )
        return Triple(m, e, head!!)
    }

    @Test
    fun `feature rows match python frame by frame`() {
        val (mel, emb, head) = requireModels()
        for (chunk in listOf(1280, 3200)) {
            val run = stream(mel, emb, head, chunk)
            val frames = meta.getInt("frames")
            assertEquals("frames at chunk $chunk", frames, run.features.size)
            var worst = 0f
            for (f in 0 until frames) {
                for (k in 0 until OwwStream.EMBEDDING_DIM) {
                    val want = pythonFeatures[f * OwwStream.EMBEDDING_DIM + k]
                    val got = run.features[f][k]
                    worst = maxOf(worst, kotlin.math.abs(want - got) / maxOf(1f, kotlin.math.abs(want)))
                }
            }
            assertEquals("worst relative feature error at chunk $chunk", 0f, worst, 2e-3f)
        }
    }

    @Test
    fun `scores match python from the first frame free of start-up rows`() {
        val (mel, emb, head) = requireModels()
        assertEquals(
            "hey_alfred.onnx is not the head the vectors were made with",
            meta.getString("head_sha256"),
            sha256(head),
        )
        val want = meta.getJSONArray("scores")
        val from = meta.getInt("first_comparable_frame")
        for (chunk in listOf(1280, 3200)) {
            val got = stream(mel, emb, head, chunk).scores
            assertEquals(want.length(), got.size)
            for (f in 0 until 5) assertEquals("start-up frame $f is zero", 0f, got[f], 0f)
            for (f in from until got.size) {
                assertEquals("frame $f at chunk $chunk", want.getDouble(f), got[f].toDouble(), 2e-3)
            }
        }
    }
}
