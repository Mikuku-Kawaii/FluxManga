package com.flux.manga.data

/** Stable generated RGB fixture, not a cryptographic random generator. */
internal class SyntheticNoisePixels {
    private var state = 0x12345678
    fun next(): Int {
        state = state xor (state shl 13)
        state = state xor (state ushr 17)
        state = state xor (state shl 5)
        return 0xff000000.toInt() or (state and 0x00ffffff)
    }
}
