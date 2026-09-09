package de.beckerrobotics.serviceroboter.core

/**
 * Zentrale Datenmodelle der Pipeline. Bewusst als reine, Android-freie Kotlin-Klassen gehalten,
 * damit dieses Modul (core) unabhängig von der Android-Laufzeit entwickelt und getestet werden kann.
 */

/** Ergebnis der Sprach-zu-Text-Umwandlung (Stufe: Spracherkennung / STT). */
data class TranscriptionResult(
    val text: String,
    val confidence: Float
)

/** Erkannte Absicht inkl. optionaler Parameter ("Slots"), z. B. Intent "erinnerung_stellen" mit Slot "uhrzeit"->"15 Uhr". */
data class IntentResult(
    val intentName: String?,
    val slots: Map<String, String> = emptyMap(),
    val confidence: Float
) {
    val isRecognized: Boolean get() = intentName != null
}

/** Ein Treffer aus der lokalen Wissensbasis (Memo/PDF), inkl. Ähnlichkeits-Score (0..1). */
data class KnowledgeHit(
    val sourceId: String,
    val chunkText: String,
    val score: Float
)

/** Antwort eines (offline oder online) Sprachmodells. */
data class GenerationResult(
    val text: String,
    val confidence: Float
)

/** Ergebnis der Datenschutz-Prüfung, bevor eine Anfrage (potenziell) das Gerät verlassen darf. */
data class SanitizedQuery(
    val text: String,
    val blocked: Boolean,
    val reason: String? = null
)

/** Welche Stufe der Pipeline die Antwort letztlich geliefert hat (u. a. für Transparenz gegenüber Nutzer:innen). */
enum class AnswerSource {
    INTENT,
    KNOWLEDGE_BASE,
    OFFLINE_LLM,
    ONLINE_FALLBACK,
    NONE
}

/** Ereignis, das während der Verarbeitung ausgelöst wird (z. B. für UI-Feedback "Ich schaue online nach..."). */
sealed class PipelineStage {
    object IntentCheck : PipelineStage()
    object KnowledgeBaseLookup : PipelineStage()
    object OfflineLlmGeneration : PipelineStage()
    object OnlineFallback : PipelineStage()
    data class Blocked(val reason: String) : PipelineStage()
}

/** Gesamtergebnis eines Pipeline-Durchlaufs. */
data class PipelineResult(
    val source: AnswerSource,
    val text: String,
    val intent: IntentResult? = null,
    val knowledgeHits: List<KnowledgeHit> = emptyList(),
    val confidence: Float = 0f
)
