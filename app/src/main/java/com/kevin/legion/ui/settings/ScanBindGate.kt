package com.kevin.legion.ui.settings

/**
 * Whether the scan pane is still on screen when the camera provider becomes ready. Leaving the pane
 * before then must not bind the camera afterwards (it would stay on until the screen is destroyed).
 */
class ScanBindGate {
    @Volatile private var disposed = false

    fun dispose() {
        disposed = true
    }

    /** Runs [block] only while the pane is still on screen. */
    fun runIfLive(block: () -> Unit) {
        if (!disposed) block()
    }
}
