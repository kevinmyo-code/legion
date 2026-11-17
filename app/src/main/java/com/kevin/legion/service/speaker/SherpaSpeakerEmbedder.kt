package com.kevin.legion.service.speaker

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * ERes2Net-en through sherpa-onnx's `SpeakerEmbeddingExtractor`, on the sherpa-onnx the wake word
 * already ships (1.13.8, onnxruntime linked into its one JNI library): no second runtime, no new
 * dependency. Used only on [SpeakerVerifier]'s single thread and never released (process-lived),
 * so no compute can ever run on a freed model.
 */
class SherpaSpeakerEmbedder private constructor(
    private val extractor: SpeakerEmbeddingExtractor,
) : SpeakerEmbedder {
    override fun embed(samples: FloatArray): FloatArray {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SpeakerMath.SAMPLE_RATE)
            stream.inputFinished()
            check(extractor.isReady(stream)) { "too little audio" }
            return extractor.compute(stream)
        } finally {
            stream.release()
        }
    }

    companion object {
        /**
         * Loads the model, or null when it is not on the phone. Throws only on a model that is there
         * but will not load; the caller treats that as missing and says so.
         */
        fun load(context: Context): SherpaSpeakerEmbedder? {
            val external = context.getExternalFilesDir(SpeakerModelFiles.DIR)?.let { File(it, SpeakerModelFiles.MODEL) }
            val extractor = when {
                external != null && external.isFile ->
                    SpeakerEmbeddingExtractor(null, config(external.absolutePath))
                SpeakerModelFiles.inAssets(context) ->
                    SpeakerEmbeddingExtractor(context.assets, config(SpeakerModelFiles.ASSET_PATH))
                else -> return null
            }
            return SherpaSpeakerEmbedder(extractor)
        }

        private fun config(model: String) = SpeakerEmbeddingExtractorConfig(model, 1, false, "cpu")
    }
}

/**
 * Where the model comes from. Not in git: the 3D-Speaker code is Apache-2.0 but no licence for the
 * model weights could be confirmed (`app/src/main/assets/speaker/README.md`). In order:
 * 1. the app's external files dir, `Android/data/com.kevin.legion/files/speaker/`, by `adb push`;
 * 2. `assets/speaker/`, gitignored, filled on the build machine before `assembleFast`.
 * Neither present: the check reports `model_missing` and the wake word opens as today.
 */
object SpeakerModelFiles {
    const val DIR = "speaker"
    const val MODEL = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
    const val ASSET_PATH = "$DIR/$MODEL"

    fun inAssets(context: Context): Boolean =
        runCatching { context.assets.list(DIR)?.contains(MODEL) == true }.getOrDefault(false)
}
