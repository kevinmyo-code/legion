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

/**
 * Starts the scanner: [init] (ML Kit's own start) then [client]. If either throws, [failed] gets
 * the exception's class name only (never its message) and no client comes back, so a phone whose
 * ML Kit cannot start shows words instead of crashing when Scan is tapped.
 */
@Suppress("TooGenericExceptionCaught") // any failure inside ML Kit's start must not crash the app
fun <T> openScanner(init: () -> Unit, client: () -> T, failed: (String) -> Unit): T? =
    try {
        init()
        client()
    } catch (e: Exception) {
        failed(e.javaClass.simpleName)
        null
    } catch (e: LinkageError) {
        // A moved sdkinternal method or a native library that will not load: still no crash.
        failed(e.javaClass.simpleName)
        null
    }
