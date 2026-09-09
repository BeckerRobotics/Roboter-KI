package de.beckerrobotics.serviceroboter.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Testfakes für die core-Ports, damit die Pipeline-Logik ohne echte KI-/Netzwerk-Anbindung geprüft werden kann. */

private class FakeIntentEngine(private val result: IntentResult) : IntentEngine {
    override suspend fun classify(text: String) = result
}

private class FakeKnowledgeBase(private val hits: List<KnowledgeHit>) : KnowledgeBase {
    var wasCalled = false
        private set
    override suspend fun search(query: String, topK: Int): List<KnowledgeHit> {
        wasCalled = true
        return hits
    }
}

private class FakeOfflineLlm(
    override val isAvailable: Boolean,
    private val result: GenerationResult
) : OfflineLanguageModel {
    var callCount = 0
        private set
    override suspend fun generate(prompt: String, context: List<KnowledgeHit>): GenerationResult {
        callCount++
        return result
    }
}

private class FakeOnlineFallback(
    override val isNetworkAvailable: Boolean,
    private val result: GenerationResult?
) : OnlineFallbackClient {
    var wasCalled = false
        private set
    override suspend fun search(query: String): GenerationResult? {
        wasCalled = true
        return result
    }
}

private class FakePrivacyFilter(private val blocked: Boolean, private val reason: String? = null) : PrivacyFilter {
    override fun sanitize(text: String) = SanitizedQuery(text, blocked, reason)
}

private val noIntent = IntentResult(intentName = null, confidence = 0f)

class ServiceRoboterPipelineTest {

    @Test
    fun `Stufe 1 liefert Antwort aus Wissensbasis und ruft weder Offline-KI noch Online-Fallback auf, wenn Offline-KI verfuegbar ist`() = runTest {
        val hits = listOf(KnowledgeHit("memo.pdf", "Die Übung beginnt um 10 Uhr im Gemeinschaftsraum.", score = 0.9f))
        val knowledgeBase = FakeKnowledgeBase(hits)
        val offlineLlm = FakeOfflineLlm(isAvailable = true, GenerationResult("Formulierte Antwort aus dem Dokument.", 0.8f))
        val onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, GenerationResult("Online-Antwort", 0.9f))

        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = knowledgeBase,
            offlineLlm = offlineLlm,
            onlineFallback = onlineFallback,
            privacyFilter = FakePrivacyFilter(blocked = false)
        )

        val result = pipeline.handle("Wann beginnt die Übung?")

        assertEquals(AnswerSource.KNOWLEDGE_BASE, result.source)
        assertEquals("Formulierte Antwort aus dem Dokument.", result.text)
        assertEquals(1, offlineLlm.callCount) // wird zur Formulierung der KB-Antwort genutzt
        assertFalse(onlineFallback.wasCalled)
    }

    @Test
    fun `Stufe 1 ohne verfuegbares Offline-LLM liefert Rohtext des besten Treffers (graceful degradation)`() = runTest {
        val hits = listOf(KnowledgeHit("memo.pdf", "Die Übung beginnt um 10 Uhr im Gemeinschaftsraum.", score = 0.9f))
        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = FakeKnowledgeBase(hits),
            offlineLlm = FakeOfflineLlm(isAvailable = false, GenerationResult("wird nicht genutzt", 0f)),
            onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, null),
            privacyFilter = FakePrivacyFilter(blocked = false)
        )

        val result = pipeline.handle("Wann beginnt die Übung?")

        assertEquals(AnswerSource.KNOWLEDGE_BASE, result.source)
        assertEquals("Die Übung beginnt um 10 Uhr im Gemeinschaftsraum.", result.text)
    }

    @Test
    fun `Stufe 2 greift wenn Wissensbasis keinen ausreichenden Treffer hat`() = runTest {
        val lowConfidenceHits = listOf(KnowledgeHit("memo.pdf", "Unwichtiger Absatz.", score = 0.1f))
        val offlineLlm = FakeOfflineLlm(isAvailable = true, GenerationResult("Freie Antwort der Offline-KI.", 0.7f))
        val onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, GenerationResult("Online-Antwort", 0.9f))

        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = FakeKnowledgeBase(lowConfidenceHits),
            offlineLlm = offlineLlm,
            onlineFallback = onlineFallback,
            privacyFilter = FakePrivacyFilter(blocked = false)
        )

        val result = pipeline.handle("Erzähl mir einen Witz.")

        assertEquals(AnswerSource.OFFLINE_LLM, result.source)
        assertEquals("Freie Antwort der Offline-KI.", result.text)
        assertFalse(onlineFallback.wasCalled)
    }

    @Test
    fun `Stufe 3 greift nur wenn nichts gefunden wurde und Datenschutz-Filter nicht blockiert`() = runTest {
        val onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, GenerationResult("Online gefundene Antwort.", 0.6f))

        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = FakeKnowledgeBase(emptyList()),
            offlineLlm = FakeOfflineLlm(isAvailable = false, GenerationResult("n/a", 0f)),
            onlineFallback = onlineFallback,
            privacyFilter = FakePrivacyFilter(blocked = false)
        )

        val result = pipeline.handle("Wie hoch ist der Eiffelturm?")

        assertEquals(AnswerSource.ONLINE_FALLBACK, result.source)
        assertEquals("Online gefundene Antwort.", result.text)
        assertTrue(onlineFallback.wasCalled)
    }

    @Test
    fun `Datenschutz-Filter verhindert Online-Fallback auch wenn Netz verfuegbar waere`() = runTest {
        val onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, GenerationResult("sollte nie ankommen", 0.9f))

        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = FakeKnowledgeBase(emptyList()),
            offlineLlm = FakeOfflineLlm(isAvailable = false, GenerationResult("n/a", 0f)),
            onlineFallback = onlineFallback,
            privacyFilter = FakePrivacyFilter(blocked = true, reason = "Gesundheitsbezug")
        )

        val result = pipeline.handle("Welche Medikamente nehme ich?")

        assertEquals(AnswerSource.NONE, result.source)
        assertFalse(onlineFallback.wasCalled)
    }

    @Test
    fun `Online-Fallback wird uebersprungen wenn global deaktiviert`() = runTest {
        val onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, GenerationResult("sollte nie ankommen", 0.9f))

        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(noIntent),
            knowledgeBase = FakeKnowledgeBase(emptyList()),
            offlineLlm = FakeOfflineLlm(isAvailable = false, GenerationResult("n/a", 0f)),
            onlineFallback = onlineFallback,
            privacyFilter = FakePrivacyFilter(blocked = false),
            config = PipelineConfig(onlineFallbackEnabled = false)
        )

        val result = pipeline.handle("Irgendeine Frage ohne Treffer.")

        assertEquals(AnswerSource.NONE, result.source)
        assertFalse(onlineFallback.wasCalled)
    }

    @Test
    fun `erkannter Intent hat Vorrang vor Wissensbasis-Suche`() = runTest {
        val knowledgeBase = FakeKnowledgeBase(listOf(KnowledgeHit("memo.pdf", "Text", score = 0.99f)))
        val pipeline = ServiceRoboterPipeline(
            intentEngine = FakeIntentEngine(IntentResult("abbrechen", confidence = 0.8f)),
            knowledgeBase = knowledgeBase,
            offlineLlm = FakeOfflineLlm(isAvailable = true, GenerationResult("n/a", 0f)),
            onlineFallback = FakeOnlineFallback(isNetworkAvailable = true, null),
            privacyFilter = FakePrivacyFilter(blocked = false)
        )

        val result = pipeline.handle("Stopp")

        assertEquals(AnswerSource.INTENT, result.source)
        assertFalse(knowledgeBase.wasCalled)
    }
}
