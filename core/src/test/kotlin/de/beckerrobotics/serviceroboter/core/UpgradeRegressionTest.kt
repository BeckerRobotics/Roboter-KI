package de.beckerrobotics.serviceroboter.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AnswerRoutingRegressionTest {
    private val calls = mutableListOf<String>()
    private var documents: List<KnowledgeHit> = emptyList()
    private var local: (List<KnowledgeHit>) -> GenerationResult = { GenerationResult("", 0f, abstained = true) }
    private var available = true
    private var intent = IntentResult(null, confidence = 0f)
    private var onlineResult: GenerationResult? = GenerationResult("Online-Antwort", 0.7f,
        webSources = listOf(WebSource("Beispiel", "https://example.org")))
    private fun pipeline(config: PipelineConfig = PipelineConfig()) = ServiceRoboterPipeline(
        object : IntentEngine { override suspend fun classify(text: String) = intent },
        object : KnowledgeBase { override suspend fun search(query: String, topK: Int): List<KnowledgeHit> {
            calls.add("documents"); return documents
        }},
        object : OfflineLanguageModel {
            override val isAvailable: Boolean get() = available
            override suspend fun generate(prompt: String, context: List<KnowledgeHit>): GenerationResult {
                calls.add(if (context.isEmpty()) "local" else "grounded")
                return local(context)
            }
        },
        object : OnlineFallbackClient {
            override val isNetworkAvailable = true
            override suspend fun search(query: String): GenerationResult? { calls.add("online"); return onlineResult }
        }, DefaultPrivacyFilter(), config
    )

    @Test fun documentQuestionDoesNotEndAtIntent() = runTest {
        intent = IntentResult("frage_dokument", confidence = 1f)
        documents = listOf(KnowledgeHit("plan.pdf", "Das Frühstück beginnt um acht Uhr.", 0.7f, 2))
        local = { GenerationResult("Das Frühstück beginnt um acht Uhr.", 0.8f, evidence = listOf("Frühstück beginnt um acht Uhr")) }
        val answer = pipeline().handle("Was steht im Dokument zum Frühstück?")
        assertEquals(AnswerSource.KNOWLEDGE_BASE, answer.source)
        assertEquals(2, answer.knowledgeHits.single().pageNumber)
        assertEquals(listOf("documents", "grounded"), calls)
    }
    @Test fun modelAbstentionFallsThroughInRequestedOrder() = runTest {
        documents = listOf(KnowledgeHit("anderes.pdf", "Es gibt Brot zum Frühstück.", 0.9f))
        pipeline().handle("Wann beginnt der Tanzkurs?")
        assertEquals(listOf("documents", "grounded", "local", "online"), calls)
    }
    @Test fun inventedEvidenceCannotBeAttributedToPdf() = runTest {
        documents = listOf(KnowledgeHit("plan.pdf", "Das Frühstück beginnt um acht Uhr.", 0.95f))
        local = { if (it.isEmpty()) GenerationResult("", 0f) else
            GenerationResult("Die Übung beginnt um elf Uhr.", 0.9f, evidence = listOf("Die Übung beginnt um elf Uhr.")) }
        assertEquals(AnswerSource.ONLINE_FALLBACK, pipeline().handle("Wann beginnt die Übung?").source)
    }
    @Test fun thresholdIsUsedInsteadOfHardcodedPointEight() = runTest {
        documents = listOf(KnowledgeHit("memo", "Der Bus fährt um neun Uhr.", 0.65f))
        available = false
        assertEquals(AnswerSource.KNOWLEDGE_BASE,
            pipeline(PipelineConfig(knowledgeConfidenceThreshold = 0.6f)).handle("Wann fährt der Bus?").source)
        assertFalse("online" in calls)
    }
    @Test fun thresholdRejectsEvenHighHitWhenConfiguredHigher() = runTest {
        documents = listOf(KnowledgeHit("memo", "Ein Text.", 0.85f))
        available = false
        assertEquals(AnswerSource.ONLINE_FALLBACK,
            pipeline(PipelineConfig(knowledgeConfidenceThreshold = 0.9f)).handle("Eine Frage").source)
    }
    @Test fun knownOfflineAnswerStopsBeforeNetwork() = runTest {
        local = { GenerationResult("Berlin ist die Hauptstadt Deutschlands.", 0.7f) }
        assertEquals(AnswerSource.OFFLINE_LLM, pipeline().handle("Wie heißt Deutschlands Hauptstadt?").source)
        assertEquals(listOf("documents", "local"), calls)
    }
    @Test fun currentWeatherCannotComeFromStaticModel() = runTest {
        local = { GenerationResult("Es sind 25 Grad.", 0.9f) }
        assertEquals(AnswerSource.ONLINE_FALLBACK, pipeline().handle("Wie ist das Wetter heute?").source)
    }
    @Test fun personalMedicationNotGuessedOrSentOnline() = runTest {
        local = { GenerationResult("Nehmen Sie zwei Tabletten.", 0.9f) }
        assertEquals(AnswerSource.NONE, pipeline().handle("Wie nehme ich meine Medikamente?").source)
        assertFalse("online" in calls)
    }
    @Test fun exceptionInLocalModelAllowsFallback() = runTest {
        local = { throw IllegalStateException("Modellfehler") }
        assertEquals(AnswerSource.ONLINE_FALLBACK, pipeline().handle("Wie hoch ist der Eiffelturm?").source)
    }
    @Test fun cancellationDoesNotTriggerNetwork() = runTest {
        local = { throw CancellationException("Stopped") }
        assertFailsWith<CancellationException> { pipeline().handle("Eine Frage") }
        assertFalse("online" in calls)
    }
    @Test fun noSourceLinkNoOnlineAnswer() = runTest {
        onlineResult = GenerationResult("Behauptung ohne Beleg", 0.9f)
        assertEquals(AnswerSource.NONE, pipeline().handle("Eine Frage").source)
    }
    @Test fun offlineTogglePreventsNetwork() = runTest {
        assertEquals(AnswerSource.NONE,
            pipeline(PipelineConfig(onlineFallbackEnabled = false)).handle("Eine Frage").source)
        assertFalse("online" in calls)
    }
    @Test fun emailNeverLeavesRobot() = runTest {
        pipeline().handle("Was weißt du über test.person@example.com?")
        assertFalse("online" in calls)
    }
}

class TokenizerRegressionTest {
    private val vocab = listOf("[PAD]","[UNK]","[CLS]","[SEP]","[MASK]","play","##ing","!","gru","##sse")
        .withIndex().associate { it.value to it.index.toLong() }
    @Test fun splitsUnknownWholeWordIntoKnownWordPieces() {
        assertContentEquals(longArrayOf(2,5,6,7,3), WordPieceTokenizer(vocab).encode("PLAYING!"))
    }
    @Test fun accentsAreNormalizedBeforeWordPiece() {
        assertContentEquals(longArrayOf(2,8,9,3), WordPieceTokenizer(vocab).encode("Grüsse"))
    }
    @Test fun unknownWordProducesOneUnknownToken() {
        assertContentEquals(longArrayOf(2,1,3), WordPieceTokenizer(vocab).encode("Unbekannt"))
    }
    @Test fun truncationKeepsSeparator() {
        assertContentEquals(longArrayOf(2,5,3), WordPieceTokenizer(vocab).encode("playing playing", 3))
    }
    @Test fun casingCanBePreserved() {
        assertContentEquals(longArrayOf(2,1,3), WordPieceTokenizer(vocab, lowercase = false).encode("PLAY"))
    }
    @Test fun invalidChunkParametersRejected() {
        assertFailsWith<IllegalArgumentException> { TextChunker.chunk("a b", 0, 0) }
        assertFailsWith<IllegalArgumentException> { TextChunker.chunk("a b", 10, -1) }
    }
}

class GermanRetrievalRegressionTest {
    private val noEmbedding = object : EmbeddingProvider {
        override val isAvailable = false
        override suspend fun embed(text: String) = FloatArray(0)
    }
    @Test fun replacementRemovesOldDocumentContents() = runTest {
        val store = InMemoryVectorStore(noEmbedding)
        store.indexDocument("memo", "Das Frühstück beginnt um sieben Uhr.")
        store.indexDocument("memo", "Das Frühstück beginnt um acht Uhr.")
        assertEquals(1, store.documentCount)
        assertEquals(1, store.chunkCount)
        assertTrue(store.search("Frühstück").single().chunkText.contains("acht"))
    }
    @Test fun germanCompoundAndPageNumberSurviveSearch() = runTest {
        val store = InMemoryVectorStore(noEmbedding)
        store.indexPages("Tagesplan.pdf", listOf(1 to "Der Garten ist geöffnet.", 2 to "Das Gedächtnistraining beginnt um zehn Uhr."))
        val hit = store.search("Wann beginnt das Gedächtnistraining?", 1).single()
        assertEquals(2, hit.pageNumber)
        assertTrue(hit.score >= 0.6f)
    }
    @Test fun zeroTopKReturnsNoHits() = runTest {
        val store = InMemoryVectorStore(noEmbedding)
        store.indexDocument("memo", "Frühstück um acht Uhr.")
        assertTrue(store.search("Frühstück", 0).isEmpty())
    }
}
