package de.beckerrobotics.serviceroboter.core

/**
 * "Ports" im Sinne einer Hexagonal-/Clean-Architektur: Schnittstellen, die von den konkreten,
 * Android-/Hardware-spezifischen Implementierungen im 'app'-Modul erfüllt werden
 * (z. B. Vosk für STT, ML Kit GenAI/AICore für das Offline-LLM, ONNX Runtime für Embeddings).
 *
 * Dieses core-Modul kennt nur die Verträge, nicht die Implementierungen. Dadurch lässt sich
 * die gesamte Entscheidungslogik (siehe ServiceRoboterPipeline) ohne Android-Gerät/-SDK
 * per JUnit testen (mit Fakes/Mocks dieser Interfaces).
 */

/** Wandelt aufgenommene Sprache in Text um (im app-Modul z. B. via Vosk, siehe Recherche-Dokument
 *  Abschnitt 2). Bewusst mit rohen Audio-Samples statt Android-spezifischen Typen (AudioRecord etc.),
 *  damit dieses Interface hier im core-Modul Android-frei bleibt. */
interface SpeechToTextEngine {
    /** true, wenn Modell geladen und einsatzbereit ist (Vosk-Modell kann u. U. asynchron laden). */
    val isReady: Boolean

    suspend fun transcribe(audioSamples: ShortArray, sampleRate: Int): TranscriptionResult
}

/** Erkennt Absichten aus freiem Text – z. B. per Keyword-/Regelwerk (siehe RuleBasedIntentEngine)
 *  oder später per Picovoice Rhino / eigenem Klassifikator. */
interface IntentEngine {
    suspend fun classify(text: String): IntentResult
}

/** Erzeugt einen Embedding-Vektor für einen Text. Implementierung im app-Modul z. B. via ONNX Runtime
 *  mit dem Modell all-MiniLM-L6-v2 (siehe Recherche-Dokument, Abschnitt 4). */
interface EmbeddingProvider {
    val isAvailable: Boolean
    suspend fun embed(text: String): FloatArray
}

/** Durchsucht die lokale Wissensbasis (aus Memo/PDF aufgebaut) nach den zur Anfrage passendsten Abschnitten. */
interface KnowledgeBase {
    suspend fun search(query: String, topK: Int = 3): List<KnowledgeHit>
}

/** Ein On-Device-Sprachmodell (z. B. Gemini Nano/AICore oder LiteRT-LM+Gemma-3n). */
interface OfflineLanguageModel {
    /** true, wenn auf diesem Gerät überhaupt verfügbar (Hardware-/API-Level-Voraussetzungen erfüllt). */
    val isAvailable: Boolean

    /** Erzeugt eine Antwort, optional gestützt auf Kontext-Abschnitte aus der Wissensbasis (RAG). */
    suspend fun generate(prompt: String, context: List<KnowledgeHit> = emptyList()): GenerationResult
}

/** Letzte Stufe: Online-Suche/-LLM. Wird nur aufgerufen, wenn Netz verfügbar UND die Anfrage
 *  den Datenschutz-Filter passiert hat. */
interface OnlineFallbackClient {
    val isNetworkAvailable: Boolean
    suspend fun search(query: String): GenerationResult?
}

/** Prüft/bereinigt eine Anfrage, bevor sie (in Stufe 3) das Gerät verlassen dürfte. */
interface PrivacyFilter {
    fun sanitize(text: String): SanitizedQuery
}
