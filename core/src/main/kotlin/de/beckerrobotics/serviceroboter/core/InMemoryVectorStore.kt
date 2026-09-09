package de.beckerrobotics.serviceroboter.core

import kotlin.math.sqrt

/**
 * Einfache, vollständig lokale Vektorsuche (Cosine-Similarity) über die Chunks der importierten
 * Memos/PDFs. Bewusst ohne externe Vektor-DB-Abhängigkeit gehalten, damit dieses Modul ohne
 * Android/ObjectBox lauffähig und testbar ist.
 *
 * Für den produktiven Android-Einsatz mit größeren Dokumentmengen ist ein Austausch gegen
 * ObjectBox Vector DB oder Zvec sinnvoll (siehe Recherche-Dokument, Abschnitt 4) – das Interface
 * [KnowledgeBase] bleibt dabei unverändert, es muss nur eine andere Implementierung eingesetzt werden.
 *
 * @param embeddingProvider liefert die Embedding-Vektoren (im 'app'-Modul z. B. via ONNX Runtime
 *        mit all-MiniLM-L6-v2; in Tests durch einen einfachen Fake ersetzbar).
 */
class InMemoryVectorStore(
    private val embeddingProvider: EmbeddingProvider
) : KnowledgeBase {

    private data class Entry(val sourceId: String, val chunkText: String, val vector: FloatArray)

    private val entries = mutableListOf<Entry>()

    /** Indexiert ein Dokument: zerlegt es in Chunks und legt für jeden Chunk einen Embedding-Eintrag an. */
    suspend fun indexDocument(sourceId: String, fullText: String) {
        val chunks = TextChunker.chunk(fullText)
        chunks.forEach { chunkText ->
            val vector = embeddingProvider.embed(chunkText)
            // Auch ohne Vektor (z.B. Modell fehlt) speichern wir den Chunk für Keyword-Suche
            entries.add(Entry(sourceId, chunkText, vector))
        }
    }

    /** Entfernt alle Chunks eines Dokuments, z. B. wenn ein Memo aktualisiert/gelöscht wurde
     *  (relevant für das Lösch-/Aufbewahrungskonzept, siehe Recherche-Dokument Abschnitt 6). */
    fun removeDocument(sourceId: String) {
        entries.removeAll { it.sourceId == sourceId }
    }

    fun clear() = entries.clear()

    val documentCount: Int get() = entries.map { it.sourceId }.distinct().size
    val chunkCount: Int get() = entries.size

    override suspend fun search(query: String, topK: Int): List<KnowledgeHit> {
        if (entries.isEmpty()) return emptyList()
        val queryVector = embeddingProvider.embed(query)

        return if (queryVector.isEmpty()) {
            // Fallback: Einfache Keyword-Suche, wenn kein Embedding-Modell verfügbar ist
            val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
            val results = entries.map { entry ->
                val textLower = entry.chunkText.lowercase()
                var matches = 0
                queryWords.forEach { if (textLower.contains(it)) matches++ }
                val score = if (queryWords.isEmpty()) 0f else matches.toFloat() / queryWords.size
                KnowledgeHit(entry.sourceId, entry.chunkText, score)
            }
            .filter { it.score > 0.05f } // Etwas toleranter
            .sortedByDescending { it.score }
            .take(topK)
            
            results
        } else {
            // Reguläre Vektor-Suche
            entries.filter { it.vector.size == queryVector.size }
                .map { KnowledgeHit(it.sourceId, it.chunkText, cosineSimilarity(queryVector, it.vector)) }
                .sortedByDescending { it.score }
                .take(topK)
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Embedding-Dimensionen stimmen nicht überein (${a.size} vs. ${b.size})." }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }
}
