package de.beckerrobotics.serviceroboter.core

import kotlinx.coroutines.CancellationException

/** Documents -> local generation -> online search. First two stages never need a network client. */
class ServiceRoboterPipeline(
    private val intentEngine: IntentEngine,
    private val knowledgeBase: KnowledgeBase,
    private val offlineLlm: OfflineLanguageModel,
    private val onlineFallback: OnlineFallbackClient,
    private val privacyFilter: PrivacyFilter,
    private val config: PipelineConfig = PipelineConfig()
) {
    suspend fun handle(userUtterance: String, onStage: (PipelineStage) -> Unit = {}): PipelineResult {
        require(userUtterance.isNotBlank())
        onStage(PipelineStage.IntentCheck)
        val intent = attempt { intentEngine.classify(userUtterance) } ?: IntentResult(null, confidence = 0f)
        if (intent.confidence >= config.intentConfidenceThreshold &&
            intent.intentName in setOf("abbrechen", "wiederholen", "hilfe")) {
            return PipelineResult(AnswerSource.INTENT, when (intent.intentName) {
                "abbrechen" -> "In Ordnung, ich höre auf."
                "wiederholen" -> "Ich wiederhole die letzte Antwort."
                else -> "Sie können mir eine Frage stellen. Ich schaue zuerst in Ihren Dokumenten nach."
            }, intent = intent, confidence = intent.confidence)
        }
        onStage(PipelineStage.KnowledgeBaseLookup)
        val hits = attempt { knowledgeBase.search(userUtterance, config.topKKnowledgeHits) }
            .orEmpty().filter { it.score.isFinite() && it.score >= config.knowledgeConfidenceThreshold }
            .sortedByDescending { it.score }
        if (hits.isNotEmpty()) {
            if (offlineLlm.isAvailable) {
                val grounded = attempt { offlineLlm.generate(userUtterance, hits) }
                // Quote matching checks provenance, not logical entailment.
                val evidenceHits = grounded?.evidence.orEmpty().filter { it.trim().length >= 8 }
                    .flatMap { quote -> hits.filter { normalized(it.chunkText).contains(normalized(quote)) } }
                    .distinct()
                if (grounded != null && usable(grounded) && evidenceHits.isNotEmpty()) {
                    return PipelineResult(AnswerSource.KNOWLEDGE_BASE, grounded.text,
                        intent, evidenceHits, hits.first().score)
                }
            } else {
                val best = hits.first()
                return PipelineResult(AnswerSource.KNOWLEDGE_BASE,
                    "Ich habe diese passende Textstelle gefunden: " + best.chunkText,
                    intent, listOf(best), best.score)
            }
        }
        if (offlineLlm.isAvailable) {
            onStage(PipelineStage.OfflineLlmGeneration)
            val answer = attempt { offlineLlm.generate(userUtterance, emptyList()) }
            if (answer != null && usable(answer) && !requiresCurrentInformation(userUtterance) &&
                !requiresPersonalRecords(userUtterance)) {
                return PipelineResult(AnswerSource.OFFLINE_LLM, answer.text, intent, confidence = answer.confidence)
            }
        }
        if (config.onlineFallbackEnabled && !requiresPersonalRecords(userUtterance)) {
            val sanitized = privacyFilter.sanitize(userUtterance)
            if (sanitized.blocked) {
                onStage(PipelineStage.Blocked(sanitized.reason ?: "Persönliche Angaben"))
                return PipelineResult(AnswerSource.NONE,
                    "Dazu habe ich lokal keine belegte Antwort gefunden. Diese persönlichen Angaben sende ich nicht ins Internet.", intent)
            }
            if (onlineFallback.isNetworkAvailable) {
                onStage(PipelineStage.OnlineFallback)
                val answer = attempt { onlineFallback.search(sanitized.text) }
                if (answer != null && usable(answer) && answer.webSources.isNotEmpty()) {
                    return PipelineResult(AnswerSource.ONLINE_FALLBACK, answer.text, intent,
                        confidence = answer.confidence, webSources = answer.webSources)
                }
            }
        }
        return PipelineResult(AnswerSource.NONE,
            "Dazu habe ich keine verlässliche Antwort gefunden. Sie können die Frage anders stellen oder eine Betreuungsperson fragen.", intent)
    }
    private fun usable(result: GenerationResult) = result.text.isNotBlank() && result.confidence.isFinite() &&
        result.confidence >= config.offlineLlmConfidenceThreshold && !result.abstained && !result.needsOnline
    private fun normalized(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()
    private fun requiresCurrentInformation(text: String) =
        Regex("\\b(heute|aktuell|derzeit|jetzt|morgen|wetter|nachrichten|spielstand|wechselkurs)\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(text)
    private fun requiresPersonalRecords(text: String) =
        Regex("\\b(mein(?:e|en|em|er|es)?|unser(?:e|en|em|er|es)?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("(termin|tablett|medikament|arzt|pflege|adresse|telefon|diagnos|passwort|konto)", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
    private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
}
