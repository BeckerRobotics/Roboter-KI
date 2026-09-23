package de.beckerrobotics.serviceroboter.core

data class TranscriptionResult(val text: String, val confidence: Float)
data class IntentResult(val intentName: String?, val slots: Map<String, String> = emptyMap(), val confidence: Float) {
    val isRecognized: Boolean get() = intentName != null
}
/** Retrieval score, not a probability that an answer is true. */
data class KnowledgeHit(val sourceId: String, val chunkText: String, val score: Float, val pageNumber: Int? = null)
data class WebSource(val title: String, val url: String)
data class GenerationResult(
    val text: String,
    /** Routing indicator only; never display as factual certainty. */
    val confidence: Float,
    val abstained: Boolean = false,
    val needsOnline: Boolean = false,
    val evidence: List<String> = emptyList(),
    val webSources: List<WebSource> = emptyList()
)
data class SanitizedQuery(val text: String, val blocked: Boolean, val reason: String? = null)
enum class AnswerSource { INTENT, KNOWLEDGE_BASE, OFFLINE_LLM, ONLINE_FALLBACK, NONE }
sealed class PipelineStage {
    object IntentCheck : PipelineStage()
    object KnowledgeBaseLookup : PipelineStage()
    object OfflineLlmGeneration : PipelineStage()
    object OnlineFallback : PipelineStage()
    data class Blocked(val reason: String) : PipelineStage()
}
data class PipelineResult(
    val source: AnswerSource, val text: String, val intent: IntentResult? = null,
    val knowledgeHits: List<KnowledgeHit> = emptyList(), val confidence: Float = 0f,
    val webSources: List<WebSource> = emptyList()
)
