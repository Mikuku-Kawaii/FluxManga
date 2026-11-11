package com.flux.manga.data

// Adapted from Keiyoushi d9420cd1c66ecd3385946b616aacfcf8f6314f70, Apache-2.0.
// Modified 2026-11-11: decompression only, bounded output; see public-web-NOTICE.txt.
internal object PublicLzString {
    private fun decompress(length: Int, resetValue: Int, getNextValue: (Int) -> Int): String {
        val dictionary = mutableListOf<String>()
        val result = StringBuilder()
        val data = DecompressionContext(resetValue, getNextValue)
        var enlargeIn = 4
        var numBits = 3
        var entry: String
        var c: Char? = null

        for (i in 0 until 3) {
            dictionary.add(i.toString())
        }

        data.loopUntilMaxPower()

        when (data.bits) {
            0 -> {
                data.bits = 0
                data.maxPower = 1 shl 8
                data.power = 1
                data.loopUntilMaxPower()
                c = data.bits.toChar()
            }
            1 -> {
                data.bits = 0
                data.maxPower = 1 shl 16
                data.power = 1
                data.loopUntilMaxPower()
                c = data.bits.toChar()
            }
            2 -> throw IllegalArgumentException("Invalid LZString")
        }

        if (c == null) {
            throw Exception("No character found")
        }

        dictionary.add(c.toString())
        var w = c.toString()
        result.append(c.toString())

        while (true) {
            if (data.index > length) {
                throw IllegalArgumentException("Invalid LZString")
            }

            data.bits = 0
            data.maxPower = 1 shl numBits
            data.power = 1
            data.loopUntilMaxPower()

            var cc = data.bits

            when (data.bits) {
                0 -> {
                    data.bits = 0
                    data.maxPower = 1 shl 8
                    data.power = 1
                    data.loopUntilMaxPower()
                    dictionary.add(data.bits.toChar().toString())
                    cc = dictionary.size - 1
                    enlargeIn--
                }
                1 -> {
                    data.bits = 0
                    data.maxPower = 1 shl 16
                    data.power = 1
                    data.loopUntilMaxPower()
                    dictionary.add(data.bits.toChar().toString())
                    cc = dictionary.size - 1
                    enlargeIn--
                }
                2 -> return result.toString()
            }

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }

            entry = if (cc < dictionary.size) {
                dictionary[cc]
            } else {
                if (cc == dictionary.size) {
                    w + w[0]
                } else {
                    throw Exception("Invalid LZString")
                }
            }
            require(result.length + entry.length <= 4 * 1024 * 1024) { "LZ data too large" }
            result.append(entry)
            dictionary.add(w + entry[0])
            enlargeIn--
            w = entry

            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
        }
    }


    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    fun decode(input: String): String {
        require(input.isNotEmpty() && input.length <= 4 * 1024 * 1024)
        return decompress(input.length, 32) { ALPHABET.indexOf(input.getOrElse(it) { throw IllegalArgumentException("Truncated LZ data") }).also { n -> require(n >= 0) } }
    }
}

private data class DecompressionContext(
    val resetValue: Int,
    val getNextValue: (Int) -> Int,
    var value: Int = getNextValue(0),
    var position: Int = resetValue,
    var index: Int = 1,
    var bits: Int = 0,
    var maxPower: Int = 1 shl 2,
    var power: Int = 1,
) {
    fun loopUntilMaxPower() {
        while (power != maxPower) {
            val resb = value and position

            position = position shr 1

            if (position == 0) {
                position = resetValue
                value = getNextValue(index++)
            }

            bits = bits or ((if (resb > 0) 1 else 0) * power)
            power = power shl 1
        }
    }
}
