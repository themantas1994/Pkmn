package pkmn.engine.util

import kotlin.random.Random

/**
 * Deterministic, seedable random source (SplitMix64). The full state is a single Long so battles
 * and encounters can be reproduced exactly in tests and replays.
 */
class Rng(seed: Long) {
    var state: Long = seed
        private set

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform integer in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        return ((nextLong() ushr 33) % bound).toInt()
    }

    /** Uniform integer in [from, until). */
    fun nextInt(from: Int, until: Int): Int = from + nextInt(until - from)

    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun nextBoolean(): Boolean = nextLong() < 0

    /** True with probability numerator/denominator. */
    fun chance(numerator: Int, denominator: Int): Boolean = nextInt(denominator) < numerator

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
    }

    fun asKotlinRandom(): Random = object : Random() {
        override fun nextBits(bitCount: Int): Int = (nextLong() ushr (64 - bitCount)).toInt()
    }

    fun fork(): Rng = Rng(nextLong())
}
