package com.kevin.legion.service

/**
 * Circuit breaker for the two-stage detector. A native abort inside sherpa-onnx cannot be caught
 * in Kotlin; it kills the process, and the service restarts into the same fault. So a marker is
 * written (synchronously) before the first native decode of a session and cleared only after
 * [OK_DECODES_TO_CLEAR] clean decodes or a clean release. A start that finds the marker set knows
 * the previous process died inside the detector, and turns the detector off.
 */
class TwoStageBreaker(private val store: Store) {
    interface Store {
        var markerSet: Boolean
        var tripped: Boolean
    }

    private var okDecodes = 0

    /** False (and trips) if the previous process died with the marker set. */
    fun shouldStart(): Boolean {
        if (!store.markerSet) return true
        store.markerSet = false
        store.tripped = true
        return false
    }

    /** Call before native work; idempotent. */
    fun markStarted() {
        if (!store.markerSet) store.markerSet = true
    }

    fun onDecodeOk() {
        if (store.markerSet && ++okDecodes >= OK_DECODES_TO_CLEAR) store.markerSet = false
    }

    fun onCleanRelease() {
        store.markerSet = false
        okDecodes = 0
    }

    companion object {
        const val OK_DECODES_TO_CLEAR = 50
    }
}
