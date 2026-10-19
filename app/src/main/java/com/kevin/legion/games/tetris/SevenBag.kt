package com.kevin.legion.games.tetris

/**
 * The piece sequence both players share (ticket 04): each bag starts `[I, J, L, O, S, T, Z]` and is
 * Fisher-Yates shuffled with `j = floor(next() * (i + 1))` for `i = 6 downTo 1`; bags concatenate
 * from ONE [Mulberry32], so the sequence is a pure function of [seed].
 */
class SevenBag(seed: Long) {
    private val rng = Mulberry32(seed)
    private val queue = ArrayDeque<Piece>()

    /** Removes and returns the next piece. */
    fun take(): Piece {
        fill(1)
        return queue.removeFirst()
    }

    /** The next [count] pieces without consuming them (the preview). */
    fun peek(count: Int): List<Piece> {
        fill(count)
        return queue.take(count)
    }

    private fun fill(count: Int) {
        while (queue.size < count) queue.addAll(shuffledBag())
    }

    private fun shuffledBag(): List<Piece> {
        val bag = Piece.entries.toMutableList()
        for (i in bag.lastIndex downTo 1) {
            val j = (rng.next() * (i + 1)).toInt()
            val swap = bag[i]
            bag[i] = bag[j]
            bag[j] = swap
        }
        return bag
    }
}
