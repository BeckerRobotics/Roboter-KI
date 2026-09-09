package de.beckerrobotics.serviceroboter.core

/**
 * Zerlegt einen (aus PDF/Memo extrahierten) Fließtext in überlappende Abschnitte ("Chunks"),
 * die einzeln eingebettet und durchsucht werden (siehe Recherche-Dokument, Abschnitt 4 – On-Device-RAG).
 *
 * Überlappung (overlapWords) verhindert, dass ein für die Antwort wichtiger Satz genau an einer
 * Chunk-Grenze "zerschnitten" wird und dadurch beim Retrieval verloren geht.
 */
object TextChunker {

    fun chunk(
        text: String,
        maxWordsPerChunk: Int = 120,
        overlapWords: Int = 20
    ): List<String> {
        require(maxWordsPerChunk > overlapWords) {
            "maxWordsPerChunk muss größer als overlapWords sein, sonst entsteht eine Endlosschleife."
        }

        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        val step = maxWordsPerChunk - overlapWords

        while (start < words.size) {
            val end = minOf(start + maxWordsPerChunk, words.size)
            chunks.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += step
        }
        return chunks
    }
}
