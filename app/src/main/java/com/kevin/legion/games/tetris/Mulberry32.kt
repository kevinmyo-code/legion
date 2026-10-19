package com.kevin.legion.games.tetris

/**
 * The race's shared RNG (ticket 04). The PWA runs the same generator on the same seed, so both
 * players get the same piece sequence. Every operation is 32-bit `Int` arithmetic: Kotlin `Int`
 * multiplication wraps exactly as JS `Math.imul` does, `ushr` is JS `>>>`, and the one place the
 * result must be treated as unsigned is the final division, hence [Integer.toUnsignedLong].
 *
 * [seed] arrives as the engine's uint32 and is truncated to its low 32 bits, which is the same bit
 * pattern the JS side holds after `| 0`.
 */
class Mulberry32(seed: Long) {
    private var state: Int = seed.toInt()

    /** The next float in `[0, 1)`. */
    fun next(): Double {
        state += INCREMENT
        var t = (state xor (state ushr SHIFT_A)) * (1 or state)
        t = (t + (t xor (t ushr SHIFT_B)) * (MIX_OR or t)) xor t
        return Integer.toUnsignedLong(t xor (t ushr SHIFT_C)).toDouble() / TWO_POW_32
    }

    private companion object {
        const val INCREMENT = 0x6D2B79F5
        const val SHIFT_A = 15
        const val SHIFT_B = 7
        const val SHIFT_C = 14
        const val MIX_OR = 61
        const val TWO_POW_32 = 4294967296.0
    }
}
