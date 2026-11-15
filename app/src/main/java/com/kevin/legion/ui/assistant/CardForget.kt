package com.kevin.legion.ui.assistant

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryWording

/** What a Forget on a memory card did: [done] only on the engine's confirmed delete. */
data class ForgetResult(val done: Boolean, val words: String)

/** Forgets one memory by id. The card's only write, and the Memory screen's own path. */
fun interface CardForget {
    suspend fun forget(id: String): ForgetResult
}

/** A test's or a host's own [CardForget]; null uses [rememberMemoryForget]. */
val LocalCardForget = staticCompositionLocalOf<CardForget?> { null }

/**
 * `MemoryController.forget`, the hard delete the Memory screen's Forget calls (ADR 0075). Said
 * "Deleted for good." only on [MemoryOutcome.Ok], the engine's 204; an unconfirmed or failed delete
 * says what did not happen, in the controller's own words.
 */
fun memoryForget(context: Context): CardForget = CardForget { id ->
    when (val outcome = MemoryController.forContext(context).forget(id)) {
        is MemoryOutcome.Ok -> ForgetResult(true, MemoryWording.DELETED)
        else -> ForgetResult(false, MemoryWording.writeFailed(outcome, "deleted"))
    }
}

@Composable
internal fun rememberMemoryForget(): CardForget {
    val app = LocalContext.current.applicationContext
    return remember(app) { memoryForget(app) }
}
