package de.beckerrobotics.serviceroboter.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministischer Fake für [EmbeddingProvider]: Bag-of-Words-Vektor über einen festen
 * "Vokabular"-Hash. Kein echtes semantisches Embedding-Modell (das ist Sache des app-Moduls
 * via ONNX Runtime/all-MiniLM-L6-v2), aber ausreichend, um die Retrieval-Logik (Cosine-Similarity,
 * Ranking) unabhängig von einem konkreten ML-Modell zu testen.
 */
private class FakeBagOfWordsEmbeddingProvider(private val dimensions: Int = 512) : EmbeddingProvider {
    override suspend fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.forEach { word ->
            val index = Math.floorMod(word.hashCode(), dimensions)
            vector[index] += 1f
        }
        return vector
    }
}

class InMemoryVectorStoreTest {

    @Test
    fun `findet den inhaltlich passendsten Chunk`() = runTest {
        val store = InMemoryVectorStore(FakeBagOfWordsEmbeddingProvider())

        store.indexDocument(
            sourceId = "memo_medikamente.pdf",
            fullText = "Die Tabletten werden morgens und abends mit ausreichend Wasser eingenommen."
        )
        store.indexDocument(
            sourceId = "memo_uebungen.pdf",
            fullText = "Die Gedächtnisübung besteht aus zehn Karten, die einander zugeordnet werden müssen."
        )

        val hits = store.search("Wann muss ich meine Tabletten nehmen?", topK = 1)

        assertEquals(1, hits.size)
        assertEquals("memo_medikamente.pdf", hits.first().sourceId)
        assertTrue(hits.first().score > 0f)
    }

    @Test
    fun `leere Wissensbasis liefert keine Treffer`() = runTest {
        val store = InMemoryVectorStore(FakeBagOfWordsEmbeddingProvider())
        assertEquals(emptyList(), store.search("irgendeine Frage"))
    }

    @Test
    fun `removeDocument entfernt nur die zugehoerigen Chunks`() = runTest {
        val store = InMemoryVectorStore(FakeBagOfWordsEmbeddingProvider())
        store.indexDocument("a.pdf", "Text von Dokument A mit einigen Worten.")
        store.indexDocument("b.pdf", "Text von Dokument B mit anderen Worten.")

        assertEquals(2, store.documentCount)
        store.removeDocument("a.pdf")
        assertEquals(1, store.documentCount)

        val hits = store.search("Dokument", topK = 10)
        assertTrue(hits.all { it.sourceId == "b.pdf" })
    }
}
