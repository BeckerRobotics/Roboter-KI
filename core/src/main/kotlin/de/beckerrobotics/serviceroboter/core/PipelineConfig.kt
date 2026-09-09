package de.beckerrobotics.serviceroboter.core

/**
 * Schwellenwerte, ab denen eine Stufe der Pipeline als "Treffer" gilt und nicht weiter eskaliert wird.
 * Zentral konfigurierbar, damit sie ohne Code-Änderung (z. B. im Rahmen der Testphase) angepasst
 * werden können.
 */
data class PipelineConfig(
    val intentConfidenceThreshold: Float = 0.34f,
    val knowledgeConfidenceThreshold: Float = 0.2f, // Von 0.55 auf 0.2 gesenkt für Keyword-Fallback
    val offlineLlmConfidenceThreshold: Float = 0.3f,
    val topKKnowledgeHits: Int = 3,
    /** Erlaubt es, den Online-Fallback global zu deaktivieren (z. B. per Konfiguration/Elternschalter). */
    val onlineFallbackEnabled: Boolean = true
)
