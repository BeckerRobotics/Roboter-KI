package de.beckerrobotics.serviceroboter.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextChunkerTest {

    @Test
    fun `kurzer Text ergibt genau einen Chunk`() {
        val chunks = TextChunker.chunk("Dies ist ein kurzer Beispieltext für ein Memo.")
        assertEquals(1, chunks.size)
    }

    @Test
    fun `langer Text wird in mehrere ueberlappende Chunks zerlegt`() {
        val longText = (1..500).joinToString(" ") { "wort$it" }
        val chunks = TextChunker.chunk(longText, maxWordsPerChunk = 100, overlapWords = 20)

        assertTrue(chunks.size > 1)

        // Überlappung prüfen: letzte Wörter von Chunk 1 tauchen am Anfang von Chunk 2 wieder auf.
        val firstChunkWords = chunks[0].split(" ")
        val secondChunkWords = chunks[1].split(" ")
        assertEquals(firstChunkWords.takeLast(20), secondChunkWords.take(20))
    }

    @Test
    fun `leerer Text ergibt keine Chunks`() {
        assertEquals(emptyList(), TextChunker.chunk("   "))
    }
}
