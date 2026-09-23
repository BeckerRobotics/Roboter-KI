package de.beckerrobotics.serviceroboter.core

import kotlin.math.sqrt

/** Hybrid German keyword and embedding retrieval with atomic document replacement. */
class InMemoryVectorStore(private val embeddingProvider: EmbeddingProvider) : KnowledgeBase {
    private data class Entry(val hit: KnowledgeHit, val vector: FloatArray, val words: Set<String>)
    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private val stopWords = setOf(
        "der","die","das","ein","eine","einen","einem","einer","und","ist","sind","mit","für","von",
        "aus","was","wie","wer","wo","wann","warum","ich","du","sie","wir","es","im","in","am",
        "an","zu","zum","zur","den","dem","des","mir","mich","kann","kannst","bitte","habe",
        "steht","dokument","pdf","memo","meine","mein","muss","soll","sollte","wird","werden"
    )
    suspend fun indexDocument(sourceId: String, fullText: String) = indexPages(sourceId, listOf(null to fullText))
    suspend fun indexPages(sourceId: String, pages: List<Pair<Int?, String>>) {
        val replacements = pages.flatMap { (page, text) ->
            TextChunker.chunk(text).map { chunk ->
                Entry(KnowledgeHit(sourceId, chunk, 0f, page), embeddingProvider.embed(chunk), words(chunk))
            }
        }
        synchronized(lock) {
            entries.removeAll { it.hit.sourceId == sourceId }
            entries.addAll(replacements)
        }
    }
    fun removeDocument(sourceId: String) { synchronized(lock) { entries.removeAll { it.hit.sourceId == sourceId } } }
    fun clear() { synchronized(lock) { entries.clear() } }
    val documentCount: Int get() = synchronized(lock) { entries.map { it.hit.sourceId }.distinct().size }
    val chunkCount: Int get() = synchronized(lock) { entries.size }
    override suspend fun search(query: String, topK: Int): List<KnowledgeHit> {
        if (topK <= 0) return emptyList()
        val snapshot = synchronized(lock) { entries.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val vector = embeddingProvider.embed(query)
        val queryWords = words(query)
        return snapshot.mapNotNull { entry ->
            val lexical = if (queryWords.isEmpty()) 0f else
                queryWords.count { word -> entry.words.any { candidate ->
                    candidate == word || (word.length >= 5 && candidate.length >= 5 &&
                        (candidate.startsWith(word) || word.startsWith(candidate)))
                } }.toFloat() / queryWords.size
            val semantic = if (vector.isNotEmpty() && vector.size == entry.vector.size)
                cosine(vector, entry.vector).coerceIn(0f, 1f) else 0f
            val score = maxOf(lexical * 0.85f, semantic * 0.85f + lexical * 0.15f)
            if (score > 0f && score.isFinite()) entry.hit.copy(score = score) else null
        }.sortedByDescending { it.score }.take(topK)
    }
    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}]+")
        .findAll(text.lowercase().replace("ß", "ss")).map { it.value }
        .filter { it.length > 2 && it !in stopWords }.toSet()
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        val denominator = sqrt(aa * bb)
        return if (denominator > 0) (dot / denominator).toFloat() else 0f
    }
}
