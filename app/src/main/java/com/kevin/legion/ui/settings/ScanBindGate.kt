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
fun <T> openScanner(init: () -> Unit, client: () -> T, failed: (String) -> Unit): T? =
    startOrReport({
        init()
        client()
    }, failed)

/**
 * Runs one start-up [step] of the scan pane (ML Kit's start, or binding the camera). If it throws,
 * [failed] gets the exception's class name only (never its message) and null comes back.
 */
@Suppress("TooGenericExceptionCaught") // any failure while starting the scanner must not crash the app
fun <T> startOrReport(step: () -> T, failed: (String) -> Unit): T? =
    try {
        step()
    } catch (e: Exception) {
        failed(e.javaClass.simpleName)
        null
    } catch (e: LinkageError) {
        // A moved sdkinternal method or a native library that will not load: still no crash.
        failed(e.javaClass.simpleName)
        null
    }

/** The back camera when the phone has one, otherwise the first other camera, otherwise none. */
fun <S : Any> pickCamera(back: S, hasBack: Boolean, others: List<S>): S? = if (hasBack) back else others.firstOrNull()

/** STUB (red step). */
class PaneCameraUses<T> {
    fun record(uses: List<T>) = Unit
    fun release(): List<T> = emptyList()
}
