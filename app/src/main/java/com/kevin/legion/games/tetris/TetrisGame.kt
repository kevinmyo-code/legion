package com.kevin.legion.games.tetris

/** One piece being steered: [x], [y] is its box's top-left on the [Board]. */
data class ActivePiece(val piece: Piece, val rotation: Int, val x: Int, val y: Int) {
    fun cells(): List<Cell> = piece.cells(rotation).map { Cell(x + it.x, y + it.y) }
}

/** Everything a screen needs to draw one frame; copies, so the game can keep moving underneath. */
class GameSnapshot(
    /** Row-major [Board.ROWS] x [Board.COLUMNS]; 0 empty, otherwise `Piece.ordinal + 1`. */
    val cells: IntArray,
    val active: ActivePiece?,
    val ghost: List<Cell>,
    val next: List<Piece>,
    val held: Piece?,
    val holdAvailable: Boolean,
    val score: Int,
    val lines: Int,
    val level: Int,
    val toppedOut: Boolean,
)

/**
 * One player's game under ticket 04's rule set: SRS rotation with wall kicks, hold once per piece,
 * ghost, five-piece preview, no T-spin/combo/back-to-back. Pure Kotlin with NO clock of its own:
 * time enters only through [advance], so a test (or a replay) is deterministic. Not thread-safe;
 * the owner drives it from one coroutine.
 *
 * Ticket 04 fixes the score, level, gravity and piece sequence (the fixture file pins them). It
 * does not fix the lock delay or the spawn cell, so those are this class's own choices and cannot
 * change a race's outcome: half a second of lock delay with 15 resets a piece, spawned centred in
 * the hidden band.
 */
class TetrisGame(seed: Long) {
    private val board = Board()
    private val bag = SevenBag(seed)
    private var current: ActivePiece? = null
    private var held: Piece? = null
    private var holdUsed = false
    private var gravityClock = 0.0
    private var lockClock = 0.0
    private var lockResets = 0

    var score = 0
        private set
    var lines = 0
        private set
    var toppedOut = false
        private set
    val level: Int get() = Scoring.level(lines)

    init {
        spawn(bag.take())
    }

    fun moveHorizontal(dx: Int): Boolean {
        val c = current ?: return false
        if (toppedOut || !board.fits(c.piece, c.rotation, c.x + dx, c.y)) return false
        current = c.copy(x = c.x + dx)
        touchLock()
        return true
    }

    /** Rotates one quarter turn, trying the SRS kicks in order; false if none fits. */
    fun rotate(clockwise: Boolean): Boolean {
        val c = current ?: return false
        if (toppedOut || c.piece == Piece.O) return false
        val target = Math.floorMod(c.rotation + if (clockwise) 1 else -1, ROTATIONS)
        for (kick in Kicks.offsets(c.piece, c.rotation, target)) {
            if (board.fits(c.piece, target, c.x + kick.x, c.y + kick.y)) {
                current = c.copy(rotation = target, x = c.x + kick.x, y = c.y + kick.y)
                touchLock()
                return true
            }
        }
        return false
    }

    /** One cell down by the player's hand: 1 point if it moved. */
    fun softDrop(): Boolean {
        val c = current ?: return false
        if (toppedOut || !board.fits(c.piece, c.rotation, c.x, c.y + 1)) return false
        current = c.copy(y = c.y + 1)
        score += Scoring.points(level, 0, 1, 0)
        gravityClock = 0.0
        return true
    }

    /** Straight to the floor and locked: 2 points per cell fallen. Returns the cells fallen. */
    fun hardDrop(): Int {
        val c = current ?: return 0
        if (toppedOut) return 0
        val landed = landingY(c)
        val fallen = landed - c.y
        score += Scoring.points(level, 0, 0, fallen)
        current = c.copy(y = landed)
        lockCurrent()
        return fallen
    }

    /** Swaps the piece with the held one (or stashes it); once per piece. */
    fun hold(): Boolean {
        val c = current ?: return false
        if (toppedOut || holdUsed) return false
        val swapIn = held
        held = c.piece
        spawn(swapIn ?: bag.take())
        holdUsed = true
        return true
    }

    /** Lets [seconds] of gravity and lock delay pass. */
    fun advance(seconds: Double) {
        if (toppedOut) return
        gravityClock += seconds
        var steps = 0
        while (!toppedOut && steps++ < MAX_STEPS_PER_ADVANCE) {
            val c = current ?: return
            if (board.fits(c.piece, c.rotation, c.x, c.y + 1)) {
                val perRow = Scoring.secondsPerRow(level)
                if (gravityClock < perRow) return
                gravityClock -= perRow
                current = c.copy(y = c.y + 1)
                lockClock = 0.0
            } else {
                lockClock += gravityClock
                gravityClock = 0.0
                if (lockClock >= LOCK_DELAY_SECONDS) lockCurrent()
                return
            }
        }
    }

    fun snapshot(): GameSnapshot {
        val c = current
        return GameSnapshot(
            cells = board.copyCells(),
            active = c,
            ghost = c?.let { it.copy(y = landingY(it)).cells() }.orEmpty(),
            next = bag.peek(PREVIEW),
            held = held,
            holdAvailable = !holdUsed,
            score = score,
            lines = lines,
            level = level,
            toppedOut = toppedOut,
        )
    }

    private fun landingY(c: ActivePiece): Int {
        var y = c.y
        while (board.fits(c.piece, c.rotation, c.x, y + 1)) y++
        return y
    }

    /** A move or turn on the floor restarts the lock delay, up to [MAX_LOCK_RESETS] times a piece. */
    private fun touchLock() {
        val c = current ?: return
        val grounded = !board.fits(c.piece, c.rotation, c.x, c.y + 1)
        if (grounded && lockResets < MAX_LOCK_RESETS) {
            lockResets++
            lockClock = 0.0
        }
    }

    private fun lockCurrent() {
        val c = current ?: return
        val lockedOut = board.place(c.piece, c.rotation, c.x, c.y)
        val levelBefore = level
        val cleared = board.clearFullRows()
        lines += cleared
        score += Scoring.points(levelBefore, cleared, 0, 0)
        holdUsed = false
        current = null
        if (lockedOut) {
            toppedOut = true
        } else {
            spawn(bag.take())
        }
    }

    private fun spawn(piece: Piece) {
        val x = (Board.COLUMNS - piece.boxSize) / 2
        gravityClock = 0.0
        lockClock = 0.0
        lockResets = 0
        if (board.fits(piece, 0, x, 0)) {
            current = ActivePiece(piece, 0, x, 0)
        } else {
            current = null
            toppedOut = true
        }
    }

    private companion object {
        const val ROTATIONS = 4
        const val PREVIEW = 5
        const val LOCK_DELAY_SECONDS = 0.5
        const val MAX_LOCK_RESETS = 15
        const val MAX_STEPS_PER_ADVANCE = 64
    }
}
