package de.beckerrobotics.serviceroboter.core

/** Byte-level BPE used by the German Jina/Roberta encoder. Case and umlauts are preserved. */
class ByteBpeTokenizer(private val vocabulary: Map<String, Long>, merges: List<Pair<String, String>>) {
    private val ranks = merges.withIndex().associate { it.value to it.index }
    private val byteSymbols: Map<Int, String> = run {
        val visible = ((33..126) + (161..172) + (174..255)).toMutableList()
        val mapped = visible.toMutableList()
        var extra = 0
        for (b in 0..255) if (b !in visible) { visible.add(b); mapped.add(256 + extra++) }
        visible.zip(mapped.map { it.toChar().toString() }).toMap()
    }
    private val pattern = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{Z}\\p{L}\\p{N}]+|[\\s\\p{Z}]+(?![^\\s\\p{Z}])|[\\s\\p{Z}]+")
    val pad: Long = vocabulary.getValue("<pad>")
    fun encode(text: String, maxLength: Int = 256): LongArray {
        require(maxLength >= 2)
        val tokens = mutableListOf(vocabulary.getValue("<s>"))
        for (match in pattern.findAll(text)) {
            val pieces = match.value.toByteArray(Charsets.UTF_8)
                .map { byteSymbols.getValue(it.toInt() and 255) }.toMutableList()
            while (pieces.size > 1) {
                val pair = (0 until pieces.lastIndex).map { pieces[it] to pieces[it + 1] }
                    .minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
                if (pair !in ranks) break
                var i = 0
                while (i < pieces.lastIndex) {
                    if (pieces[i] == pair.first && pieces[i + 1] == pair.second) {
                        pieces[i] = pieces[i] + pieces[i + 1]
                        pieces.removeAt(i + 1)
                    }
                    i++
                }
            }
            for (piece in pieces) {
                if (tokens.size >= maxLength - 1) break
                tokens.add(vocabulary[piece] ?: vocabulary.getValue("<unk>"))
            }
            if (tokens.size >= maxLength - 1) break
        }
        tokens.add(vocabulary.getValue("</s>"))
        return tokens.toLongArray()
    }
}
