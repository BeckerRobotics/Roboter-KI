package de.beckerrobotics.serviceroboter.core

import java.text.Normalizer
import java.util.Locale

/** BERT BasicTokenizer + greedy WordPiece. IDs remain aligned with the unchanged vocab.txt. */
class WordPieceTokenizer(
    private val vocabulary: Map<String, Long>,
    private val lowercase: Boolean = true,
    private val stripAccents: Boolean = lowercase
) {
    private val unknown = vocabulary.getValue("[UNK]")
    private val cls = vocabulary.getValue("[CLS]")
    private val sep = vocabulary.getValue("[SEP]")
    val pad: Long = vocabulary.getValue("[PAD]")
    fun encode(text: String, maxLength: Int = 256): LongArray {
        require(maxLength >= 2)
        val result = mutableListOf(cls)
        val cleaned = buildString {
            text.codePoints().forEach { cp ->
                when {
                    cp == 0 || cp == 0xfffd -> Unit
                    Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> append(' ')
                    Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                    isChinese(cp) || isPunctuation(cp) -> { append(' '); appendCodePoint(cp); append(' ') }
                    else -> appendCodePoint(cp)
                }
            }
        }
        for (word in cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var normalized = if (lowercase) word.lowercase(Locale.ROOT) else word
            if (stripAccents) normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
            for (piece in splitWord(normalized)) {
                if (result.size >= maxLength - 1) break
                result.add(piece)
            }
            if (result.size >= maxLength - 1) break
        }
        result.add(sep)
        return result.toLongArray()
    }
    private fun splitWord(word: String): List<Long> {
        if (word.codePointCount(0, word.length) > 100) return listOf(unknown)
        val pieces = mutableListOf<Long>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found: Long? = null
            while (start < end) {
                found = vocabulary[(if (start > 0) "##" else "") + word.substring(start, end)]
                if (found != null) break
                end = word.offsetByCodePoints(end, -1)
            }
            if (found == null) return listOf(unknown)
            pieces.add(found)
            start = end
        }
        return pieces
    }
    private fun isPunctuation(cp: Int): Boolean =
        cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
            Character.getType(cp) in setOf(20, 21, 22, 23, 24, 29, 30)
    private fun isChinese(cp: Int) = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
        cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
        cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
}
