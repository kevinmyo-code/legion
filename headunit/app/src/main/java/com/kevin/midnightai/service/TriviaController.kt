package com.kevin.midnightai.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ephemeral driver-vs-passenger trivia game state, driven by [LiveToolbox]'s
 * `start_trivia_game` / `award_point` / `end_trivia_game` voice tools.
 *
 * Deliberately not persisted (`.scratch/trivia-game/map.md`, "Persistence" decision) - no Room
 * table, no recap/Wrapped surfacing. Scores live in memory for the game's duration and are
 * discarded on [end] or [endIfActive].
 *
 * Exposed as a [StateFlow] because the scoreboard
 * overlay needs to recompose on every point.
 */
object TriviaController {

    data class Game(
        val category: String,
        val questionCount: Int,
        val driverName: String,
        val passengerName: String,
        val driverScore: Int = 0,
        val passengerScore: Int = 0,
    )

    private val _current = MutableStateFlow<Game?>(null)
    val current: StateFlow<Game?> = _current.asStateFlow()

    val isActive: Boolean get() = _current.value != null

    // One-shot signal for the celebration overlay (ticket 03) - `current` going null on its own
    // can't distinguish "game just ended" from "no game ever started," so the UI needs an event.
    private val _ended = MutableSharedFlow<Game>(extraBufferCapacity = 1)
    val ended: SharedFlow<Game> = _ended.asSharedFlow()

    fun start(
        category: String,
        questionCount: Int,
        driverName: String,
        passengerName: String,
    ): String {
        val game = Game(
            category = category.ifBlank { "general knowledge" },
            questionCount = if (questionCount > 0) questionCount else 10,
            driverName = driverName.ifBlank { "Driver" },
            passengerName = passengerName.ifBlank { "Passenger" },
        )
        _current.value = game
        return "Trivia game started: ${game.category}, ${game.questionCount} questions, " +
            "${game.driverName} vs ${game.passengerName}."
    }

    fun awardPoint(who: String): String {
        val game = _current.value ?: return "No trivia game is running."
        val updated = when (who.lowercase()) {
            "driver" -> game.copy(driverScore = game.driverScore + 1)
            "passenger" -> game.copy(passengerScore = game.passengerScore + 1)
            else -> return "I don't know who '$who' is - say driver or passenger."
        }
        _current.value = updated
        return "Point to ${if (who.lowercase() == "driver") updated.driverName else updated.passengerName}. " +
            "${updated.driverName} ${updated.driverScore} - ${updated.passengerScore} ${updated.passengerName}."
    }

    /** Explicit end, via the `end_trivia_game` tool. */
    fun end(): String {
        val game = _current.value ?: return "No trivia game is running."
        _current.value = null
        _ended.tryEmit(game)
        return finalScoreLine(game)
    }

    /**
     * Auto-end when the drive stops (`AriaForegroundService.startDriveMonitor`). Returns the
     * final [Game] snapshot (for building a proactive celebration line) or null if no game was
     * running - distinct from [end] because the caller isn't a tool response, it's a background
     * loop that needs the raw scores to phrase its own proactive prompt.
     */
    fun endIfActive(): Game? {
        val game = _current.value ?: return null
        _current.value = null
        _ended.tryEmit(game)
        return game
    }

    private fun finalScoreLine(game: Game): String {
        val winner = if (game.driverScore >= game.passengerScore) game.driverName else game.passengerName
        return "Game over. $winner wins, ${game.driverName} ${game.driverScore} - " +
            "${game.passengerScore} ${game.passengerName}."
    }
}
