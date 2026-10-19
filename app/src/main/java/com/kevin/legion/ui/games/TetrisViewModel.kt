package com.kevin.legion.ui.games

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kevin.legion.games.race.GamesBackend
import com.kevin.legion.games.race.MatchState
import com.kevin.legion.games.race.NO_ENGINE_SENTENCE
import com.kevin.legion.games.race.PlayerProgress
import com.kevin.legion.games.race.RacePhase
import com.kevin.legion.games.race.clockOffsetMs
import com.kevin.legion.games.race.djangoGamesBackendOrNull
import com.kevin.legion.games.race.engineSentence
import com.kevin.legion.games.race.racePhase
import com.kevin.legion.games.race.resultSentence
import com.kevin.legion.games.tetris.GameSnapshot
import com.kevin.legion.games.tetris.TetrisGame
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the Tetris screen was entered (see the three routes in `LegionRoute`). */
sealed interface TetrisMode {
    data object Solo : TetrisMode

    /** Invite this household member (engine user id) and wait for their answer. */
    data class Invite(val userId: String) : TetrisMode

    /** An existing match: the invite notification's destination. */
    data class Match(val matchId: String) : TetrisMode
}

enum class TetrisStage { STARTING, INCOMING, WAITING, CLOSED, COUNTDOWN, PLAYING, FINISHING, RESULT, SOLO_OVER }

/**
 * Everything the Tetris screen draws. [message] is the headline in words for every stage that is
 * not a live board; [error] is the latest thing that did NOT happen, kept apart so a failed call
 * never reads as a stage change.
 */
data class TetrisUiState(
    val stage: TetrisStage = TetrisStage.STARTING,
    val race: Boolean = false,
    val message: String = "Getting ready...",
    val error: String? = null,
    val secondsLeft: Int = 0,
    val board: GameSnapshot? = null,
    val opponentName: String = "",
    val opponentScore: Int = 0,
    val opponentToppedOut: Boolean = false,
    val busy: Boolean = false,
    /** Solo only: gravity, lock delay and input are stopped and the pause overlay is up. */
    val paused: Boolean = false,
    val theme: TetrisTheme = TetrisTheme.CLASSIC,
    /** What the engine said happened to the invite's notification; shown while waiting. */
    val inviteNote: String? = null,
)

private const val POLL_MS = 1_000L
private const val FRAME_MS = 16L
private const val IDLE_TICK_MS = 100L
private const val MAX_FRAME_SECONDS = 0.1
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val SEED_RANGE = 1L shl 32

/**
 * The Tetris screen's ViewModel (retro-games ticket 06): one [StateFlow] of [TetrisUiState], per
 * CLAUDE.md section 8. No Hilt yet, matching [com.kevin.legion.ui.home.GamesViewModel]; the
 * backend is resolved from the application context here and is the seam a test would replace.
 *
 * Solo is a local game with no clock but gravity and never touches the engine. A race has two
 * coroutines: a 1 s poll that fetches the match, corrects the clock offset and sends my progress,
 * and a frame loop that derives the phase from the latest match and steps my game. The frame loop
 * never waits on the network, so a slow engine can stall the opponent's score but not my pieces.
 * Both run on the main dispatcher, so the game needs no locking.
 */
@Suppress("TooManyFunctions") // one function per user action and per race step; splitting would share all the state
class TetrisViewModel @JvmOverloads constructor(
    app: Application,
    /** The frame clock; a test passes its own so gravity is deterministic. */
    private val nanoClock: () -> Long = System::nanoTime,
) : AndroidViewModel(app) {
    private val themeStore = TetrisThemeStore(app)
    private var theme = themeStore.load()
    private val _state = MutableStateFlow(TetrisUiState(theme = theme))
    val state: StateFlow<TetrisUiState> = _state.asStateFlow()

    private var started = false
    private var game: TetrisGame? = null
    private var backend: GamesBackend? = null
    private var matchId: String? = null
    private var asInviter = false
    private var latest: MatchState? = null
    private var offsetMs = 0L
    private var finishedLocally = false
    private var paused = false
    private var frameJob: Job? = null
    private var pollJob: Job? = null

    /** Idempotent: a recomposition or rotation that calls again does nothing. */
    fun start(mode: TetrisMode) {
        if (started) return
        started = true
        when (mode) {
            TetrisMode.Solo -> startSolo()
            is TetrisMode.Invite -> startInvite(mode.userId)
            is TetrisMode.Match -> openMatch(mode.matchId)
        }
    }

    // ---- solo

    private fun startSolo() {
        val g = TetrisGame(Random.nextLong(0L, SEED_RANGE))
        game = g
        paused = false
        _state.value = TetrisUiState(stage = TetrisStage.PLAYING, race = false, board = g.snapshot(), theme = theme)
        frameJob?.cancel()
        frameJob = viewModelScope.launch { soloLoop(g) }
    }

    fun playAgain() = startSolo()

    /**
     * Solo only. Races never pause: the clock is the engine's and shared with the other player, so
     * a local freeze would only let one side think. Gravity, lock delay and input all stop because
     * the solo loop stops calling [TetrisGame.advance] and [act] refuses while paused.
     */
    fun pause() {
        val s = _state.value
        if (s.race || s.stage != TetrisStage.PLAYING || paused) return
        paused = true
        releaseHolds()
        _state.update { it.copy(paused = true) }
    }

    fun resume() {
        if (!paused) return
        paused = false
        _state.update { it.copy(paused = false) }
    }

    fun togglePause() = if (paused) resume() else pause()

    /** The app left the foreground (ON_STOP): pause, and stay paused until the user presses Resume. */
    fun autoPause() = pause()

    fun setTheme(value: TetrisTheme) {
        theme = value
        themeStore.save(value)
        _state.update { it.copy(theme = value) }
    }

    fun cycleTheme() = setTheme(theme.next())

    // ---- the pad: touch buttons and hardware keys both land here

    private val holds = mapOf(
        PadButton.LEFT to HoldRepeat(viewModelScope) { moveLeft() },
        PadButton.RIGHT to HoldRepeat(viewModelScope) { moveRight() },
        PadButton.DOWN to HoldRepeat(viewModelScope) { softDrop() },
    )

    fun padDown(button: PadButton) {
        when (button) {
            PadButton.LEFT, PadButton.RIGHT, PadButton.DOWN -> holds.getValue(button).press()
            PadButton.UP -> hardDrop()
            PadButton.A -> rotate(clockwise = true)
            PadButton.B -> rotate(clockwise = false)
            PadButton.HOLD -> hold()
            PadButton.START -> togglePause()
            PadButton.SELECT -> cycleTheme()
        }
    }

    fun padUp(button: PadButton) {
        holds[button]?.release()
    }

    private fun releaseHolds() = holds.values.forEach { it.release() }

    private suspend fun soloLoop(g: TetrisGame) {
        var last = nanoClock()
        while (viewModelScope.isActive) {
            val now = nanoClock()
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            if (!paused) g.advance(dt)
            if (g.toppedOut) {
                _state.update {
                    it.copy(
                        stage = TetrisStage.SOLO_OVER,
                        board = g.snapshot(),
                        message = "Topped out at ${g.score} points, ${g.lines} lines.",
                    )
                }
                return
            }
            _state.update { it.copy(board = g.snapshot()) }
            delay(FRAME_MS)
        }
    }

    // ---- race: entering

    private fun startInvite(userId: String) {
        val engine = engineOrClosed() ?: return
        _state.value = TetrisUiState(
            stage = TetrisStage.STARTING, race = true, message = "Sending the invite...", theme = theme,
        )
        viewModelScope.launch {
            engine.invite(userId).fold(
                onSuccess = { sent ->
                    asInviter = true
                    // The engine's own sentence about the notification (for example "no device
                    // signed up for them"), kept on the waiting screen so the inviter knows.
                    _state.update { it.copy(inviteNote = sent.note.takeIf(String::isNotBlank)) }
                    follow(sent.matchId)
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            stage = TetrisStage.CLOSED,
                            message = "The invite was not sent.",
                            error = e.engineSentence(),
                        )
                    }
                },
            )
        }
    }

    private fun openMatch(id: String) {
        if (engineOrClosed() == null) return
        asInviter = false
        _state.value = TetrisUiState(
            stage = TetrisStage.STARTING, race = true, message = "Opening the invite...", theme = theme,
        )
        follow(id)
    }

    /** The engine, or the CLOSED screen saying in words that a race cannot start without one. */
    private fun engineOrClosed(): GamesBackend? {
        val engine = djangoGamesBackendOrNull(getApplication<Application>())
        backend = engine
        if (engine == null) {
            _state.value = TetrisUiState(
                stage = TetrisStage.CLOSED, race = true, message = NO_ENGINE_SENTENCE, theme = theme,
            )
        }
        return engine
    }

    private fun follow(id: String) {
        matchId = id
        pollJob?.cancel()
        frameJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(POLL_MS)
            }
        }
        frameJob = viewModelScope.launch { raceLoop() }
    }

    // ---- race: talking to the engine

    private suspend fun refresh() {
        val engine = backend ?: return
        val id = matchId ?: return
        engine.match(id).fold(
            onSuccess = { m ->
                offsetMs = clockOffsetMs(m, System.currentTimeMillis())
                latest = m
                _state.update { it.copy(error = null) }
            },
            onFailure = { e ->
                _state.update { it.copy(error = "Lost touch with the engine, trying again. ${e.engineSentence()}") }
            },
        )
        reportProgress()
    }

    private suspend fun reportProgress() {
        val engine = backend
        val id = matchId
        val g = game
        if (engine == null || id == null || g == null) return
        if (_state.value.stage == TetrisStage.RESULT) return
        engine.progress(
            id,
            PlayerProgress(g.score, g.lines, g.level, toppedOut = g.toppedOut, finished = finishedLocally),
        ).onFailure { e ->
            _state.update { it.copy(error = "Your score was not sent this time. ${e.engineSentence()}") }
        }
    }

    fun accept() = answer { engine, id ->
        engine.accept(id).fold(
            onSuccess = { refresh() },
            onFailure = { e -> failAnswer("The invite was not accepted.", e) },
        )
    }

    fun decline() = answer { engine, id ->
        engine.decline(id).fold(
            onSuccess = { closeWith("You declined the race.") },
            onFailure = { e -> failAnswer("The invite was not declined.", e) },
        )
    }

    fun cancel() = answer { engine, id ->
        engine.cancel(id).fold(
            onSuccess = { closeWith("You cancelled the invite.") },
            onFailure = { e -> failAnswer("The invite was not cancelled.", e) },
        )
    }

    private fun answer(call: suspend (GamesBackend, String) -> Unit) {
        val engine = backend
        val id = matchId
        if (engine == null || id == null || _state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            call(engine, id)
            _state.update { it.copy(busy = false) }
        }
    }

    private fun failAnswer(what: String, e: Throwable) {
        _state.update { it.copy(error = "$what ${e.engineSentence()}") }
    }

    private fun closeWith(message: String) {
        pollJob?.cancel()
        frameJob?.cancel()
        _state.update { it.copy(stage = TetrisStage.CLOSED, message = message, error = null) }
    }

    // ---- race: the frame loop

    private suspend fun raceLoop() {
        var last = nanoClock()
        while (viewModelScope.isActive) {
            val now = nanoClock()
            val dt = ((now - last) / NANOS_PER_SECOND).coerceAtMost(MAX_FRAME_SECONDS)
            last = now
            latest?.let { step(it, dt) }
            delay(if (_state.value.stage == TetrisStage.PLAYING) FRAME_MS else IDLE_TICK_MS)
        }
    }

    private fun step(m: MatchState, dt: Double) {
        val serverNow = System.currentTimeMillis() + offsetMs
        when (val phase = racePhase(m, asInviter, serverNow)) {
            is RacePhase.Incoming -> show(
                TetrisStage.INCOMING, "${phase.fromName} invited you to a three minute Tetris race.", m,
            )
            is RacePhase.Waiting -> show(TetrisStage.WAITING, "Waiting for ${phase.opponentName} to answer...", m)
            is RacePhase.Closed -> {
                show(TetrisStage.CLOSED, phase.sentence, m)
                pollJob?.cancel()
                frameJob?.cancel()
            }
            is RacePhase.Countdown -> {
                val g = ensureGame(m)
                val text = "Starting in ${phase.secondsLeft}"
                show(TetrisStage.COUNTDOWN, text, m, secondsLeft = phase.secondsLeft, board = g)
            }
            is RacePhase.Playing -> play(m, phase.secondsLeft, dt)
            RacePhase.TimeUp -> {
                finishRace()
                show(TetrisStage.FINISHING, "Time. Waiting for the engine to call the race.", m, board = game)
            }
            is RacePhase.Finished -> finished(m, phase)
        }
    }

    private fun ensureGame(m: MatchState): TetrisGame = game ?: TetrisGame(m.seed).also { game = it }

    private fun play(m: MatchState, secondsLeft: Int, dt: Double) {
        val g = ensureGame(m)
        if (finishedLocally) {
            show(TetrisStage.FINISHING, "You topped out at ${g.score}. Waiting for ${m.opponentName}.", m, board = g)
            return
        }
        g.advance(dt)
        if (g.toppedOut) {
            finishRace()
            show(TetrisStage.FINISHING, "You topped out at ${g.score}. Waiting for ${m.opponentName}.", m, board = g)
        } else {
            show(TetrisStage.PLAYING, "", m, secondsLeft = secondsLeft, board = g)
        }
    }

    /** My game is over: say so to the engine now rather than on the next poll. */
    private fun finishRace() {
        if (finishedLocally) return
        finishedLocally = true
        viewModelScope.launch { reportProgress() }
    }

    private fun finished(m: MatchState, phase: RacePhase.Finished) {
        val mine = if (m.me.score > 0 || game == null) m.me.score else game?.score ?: 0
        show(TetrisStage.RESULT, resultSentence(phase.result, mine, m.opponent.score, m.opponentName), m, board = game)
        pollJob?.cancel()
        frameJob?.cancel()
    }

    private fun show(
        stage: TetrisStage,
        message: String,
        m: MatchState,
        secondsLeft: Int = 0,
        board: TetrisGame? = null,
    ) {
        _state.update {
            it.copy(
                stage = stage,
                race = true,
                message = message,
                secondsLeft = secondsLeft,
                board = board?.snapshot() ?: it.board,
                opponentName = m.opponentName,
                opponentScore = m.opponent.score,
                opponentToppedOut = m.opponent.toppedOut,
            )
        }
    }

    // ---- input, from the board's gestures and the buttons

    fun moveLeft() = act { it.moveHorizontal(-1) }

    fun moveRight() = act { it.moveHorizontal(1) }

    fun rotate(clockwise: Boolean = true) = act { it.rotate(clockwise) }

    fun softDrop() = act { it.softDrop() }

    fun hardDrop() = act { it.hardDrop() }

    fun hold() = act { it.hold() }

    private fun act(move: (TetrisGame) -> Unit) {
        val g = game ?: return
        if (_state.value.stage != TetrisStage.PLAYING || paused) return
        move(g)
        _state.update { it.copy(board = g.snapshot()) }
    }
}
