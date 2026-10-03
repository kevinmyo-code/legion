package com.kevin.legion.service

/** The slice of a streaming keyword spotter [KwsFeeder] needs, so the ready loop is testable without native code. */
interface KeywordStreamPort {
    fun accept(samples: FloatArray)
    fun isReady(): Boolean
    fun decode()
    fun keyword(): String

    /** Throw the current stream away and start a clean one (never a mid-utterance reset). */
    fun fresh()
}

/**
 * The feed/decode loop, exactly as sherpa-onnx's own Android KWS example runs it. The A25 crashed
 * (native Ort::Exception, Reshape node "requested shape {8,2,1,128}" against {17,1,128}) when a
 * streaming zipformer was driven out of step with its frame chunking, so the rules are: decode
 * ONLY while [KeywordStreamPort.isReady]; on a hit take a fresh stream instead of resetting the
 * old one; never decode a partial tail.
 */
object KwsFeeder {
    /** Feeds [samples]; returns true on a keyword hit (the stream has been replaced by then). */
    fun feed(port: KeywordStreamPort, samples: FloatArray): Boolean {
        port.accept(samples)
        var hit = false
        while (!hit && port.isReady()) {
            port.decode()
            if (port.keyword().isNotBlank()) {
                hit = true
                port.fresh()
            }
        }
        return hit
    }
}
