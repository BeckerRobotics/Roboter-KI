package de.beckerrobotics.serviceroboter.core

/**
 * Herzstück des Prototyps: setzt die vom Nutzer gewünschte Priorisierung technisch um
 *
 *   1. Lokale Wissensbasis aus Memo/PDF   (höchste Priorität)
 *   2. Offline-KI (freies Sprachverständnis)
 *   3. Online-Suche/-LLM                  (nur wenn erlaubt, Netz vorhanden und datenschutzunbedenklich)
 *
 * plus einer vorgelagerten, schnellen Intent-Erkennung für klar definierte Befehle (siehe
 * Recherche-Dokument, Abschnitt 3.1) – das entspricht "Roboter versteht auch Formulierungsvarianten,
 * nicht nur exakte Kommandowörter", ohne für jede Kleinigkeit gleich ein großes Sprachmodell zu bemühen.
 *
 * Alle Abhängigkeiten sind Interfaces ([IntentEngine], [KnowledgeBase], [OfflineLanguageModel],
 * [OnlineFallbackClient], [PrivacyFilter]) – dadurch ist diese Klasse vollständig ohne Android-Gerät
 * testbar (siehe core/src/test).
 */
class ServiceRoboterPipeline(
    private val intentEngine: IntentEngine,
    private val knowledgeBase: KnowledgeBase,
    private val offlineLlm: OfflineLanguageModel,
    private val onlineFallback: OnlineFallbackClient,
    private val privacyFilter: PrivacyFilter,
    private val config: PipelineConfig = PipelineConfig()
) {

    suspend fun handle(userUtterance: String, onStage: (PipelineStage) -> Unit = {}): PipelineResult {
        require(userUtterance.isNotBlank()) { "userUtterance darf nicht leer sein." }

        onStage(PipelineStage.IntentCheck)
        val intent = intentEngine.classify(userUtterance)
        if (intent.isRecognized && intent.confidence >= config.intentConfidenceThreshold) {
            return PipelineResult(
                source = AnswerSource.INTENT,
                text = describeIntent(intent),
                intent = intent,
                confidence = intent.confidence
            )
        }

        // Stufe 1 – höchste Priorität lt. Anforderung: erst in Memo/PDF-Wissensbasis nachschauen.
        onStage(PipelineStage.KnowledgeBaseLookup)
        val hits = knowledgeBase.search(userUtterance, config.topKKnowledgeHits)
        val bestHit = hits.firstOrNull()
        if (bestHit != null) {
            // Logik-Check: Nur wenn Score hoch genug
            if (bestHit.score >= config.knowledgeConfidenceThreshold) {
                val answerText = if (offlineLlm.isAvailable) {
                    offlineLlm.generate(userUtterance, hits).text
                } else {
                    bestHit.chunkText
                }
                return PipelineResult(
                    source = AnswerSource.KNOWLEDGE_BASE,
                    text = answerText,
                    intent = intent,
                    knowledgeHits = hits,
                    confidence = bestHit.score
                )
            }
        }

        // Stufe 2 – zweite Priorität: Offline-KI für freies Sprachverständnis.
        if (offlineLlm.isAvailable) {
            onStage(PipelineStage.OfflineLlmGeneration)
            val generation = offlineLlm.generate(userUtterance)
            if (generation.confidence >= config.offlineLlmConfidenceThreshold) {
                return PipelineResult(
                    source = AnswerSource.OFFLINE_LLM,
                    text = generation.text,
                    intent = intent,
                    knowledgeHits = hits,
                    confidence = generation.confidence
                )
            }
        }

        // Stufe 3 – letzte Option: Online-Fallback, nur nach Datenschutz-Prüfung und mit Netz.
        if (config.onlineFallbackEnabled) {
            val sanitized = privacyFilter.sanitize(userUtterance)
            if (sanitized.blocked) {
                onStage(PipelineStage.Blocked(sanitized.reason ?: "Datenschutz-Filter"))
                return PipelineResult(
                    source = AnswerSource.NONE,
                    text = "Aus Datenschutzgründen darf ich diese Frage nicht online stellen: ${sanitized.reason}",
                    intent = intent,
                    knowledgeHits = hits,
                    confidence = 0f
                )
            }
            
            if (onlineFallback.isNetworkAvailable) {
                onStage(PipelineStage.OnlineFallback)
                val result = onlineFallback.search(sanitized.text)
                if (result != null) {
                    return PipelineResult(
                        source = AnswerSource.ONLINE_FALLBACK,
                        text = result.text,
                        intent = intent,
                        knowledgeHits = hits,
                        confidence = result.confidence
                    )
                }
            }
        }

        return PipelineResult(
            source = AnswerSource.NONE,
            text = "Entschuldigung, dazu konnte ich leider nichts finden.",
            intent = intent,
            knowledgeHits = hits,
            confidence = 0f
        )
    }

    /**
     * Platzhalter-Formulierung für erkannte Befehle. In der App wird ein erkannter Intent in der
     * Regel zusätzlich eine echte Aktion auslösen (Übung starten, Erinnerung stellen, ...) –
     * das ist bewusst nicht Teil dieses core-Moduls, sondern gehört ins app-Modul (Aktionen sind
     * eng mit Robotersteuerung/Android-Framework verzahnt).
     */
    private fun describeIntent(intent: IntentResult): String = when (intent.intentName) {
        "uebung_starten" -> "Alles klar, ich starte die Übung."
        "erinnerung_stellen" -> {
            val uhrzeit = intent.slots["uhrzeit"] ?: "später"
            val inhalt = intent.slots["inhalt"] ?: "das Gewünschte"
            "Ich erinnere dich um $uhrzeit an $inhalt."
        }
        "frage_dokument" -> "Ich schaue im Dokument nach."
        "wiederholen" -> "Klar, ich wiederhole das."
        "abbrechen" -> "Alles klar, ich höre auf."
        "hilfe" -> "Ich kann dir bei Übungen, Erinnerungen und Fragen zu deinen Dokumenten helfen."
        else -> "Verstanden."
    }
}
