package com.flux.manga.data

import java.security.MessageDigest

/** Offline protocol metadata only; does not register or authorize a real source.
 * MIT adaptation: assets/licenses/jm-image-NOTICE.txt. */
internal object JmImageRules {
    fun strips(scrambleId: Long, chapterId: Long, filenameStem: String): Int {
        require(scrambleId >= 0 && chapterId >= 0 && filenameStem.isNotBlank())
        require(filenameStem.length <= 256 && filenameStem.none { it == '/' || it == '\\' || it.isISOControl() })
        if (chapterId < scrambleId) return 0
        if (chapterId < 268850) return 10
        val digest = MessageDigest.getInstance("MD5")
            .digest("$chapterId$filenameStem".toByteArray(Charsets.UTF_8))
        val lastHexCharacter = "0123456789abcdef"[digest.last().toInt() and 15]
        val modulus = if (chapterId < 421926) 10 else 8
        return (lastHexCharacter.code % modulus) * 2 + 2
    }
}
