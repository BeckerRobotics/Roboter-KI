package de.beckerrobotics.serviceroboter.app

import android.app.Application
import de.beckerrobotics.serviceroboter.app.llm.LlamaCppLanguageModel
import de.beckerrobotics.serviceroboter.app.network.SimpleOnlineFallbackClient
import de.beckerrobotics.serviceroboter.app.rag.*
import de.beckerrobotics.serviceroboter.app.stt.VoskSttEngine
import de.beckerrobotics.serviceroboter.app.tts.TtsProvider
import de.beckerrobotics.serviceroboter.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReadyState(val ready: Boolean = false, val message: String = "Der Roboter wird vorbereitet.")

class ServiceRoboterApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val documentMutex = Mutex()
    val readiness = MutableStateFlow(ReadyState())
    lateinit var settings: AppSettings; private set
    lateinit var sttEngine: VoskSttEngine; private set
    lateinit var vectorStore: InMemoryVectorStore; private set
    lateinit var pipeline: ServiceRoboterPipeline; private set
    lateinit var pdfIngestor: PdfIngestor; private set
    lateinit var ttsProvider: TtsProvider; private set
    lateinit var offlineLlm: LlamaCppLanguageModel; private set
    lateinit var embeddingProvider: OnnxEmbeddingProvider; private set
    val isReady: Boolean get() = readiness.value.ready

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        ttsProvider = TtsProvider(this, settings)
        embeddingProvider = OnnxEmbeddingProvider(this)
        vectorStore = InMemoryVectorStore(embeddingProvider)
        pdfIngestor = PdfIngestor(this)
        sttEngine = VoskSttEngine(this)
        offlineLlm = LlamaCppLanguageModel(this)
        pipeline = ServiceRoboterPipeline(
            RuleBasedIntentEngine(RuleBasedIntentEngine.defaultIntents()),
            vectorStore, offlineLlm, SimpleOnlineFallbackClient(this, settings, offlineLlm),
            DefaultPrivacyFilter()
        )
        scope.launch {
            try {
                readiness.value = ReadyState(message = "Deutsche Dokumentensuche wird geladen.")
                embeddingProvider.initialize()
                reloadDocuments()
                readiness.value = ReadyState(message = "Spracherkennung wird geladen.")
                sttEngine.initialize()
                readiness.value = ReadyState(message = "Lokales Sprachmodell wird geladen.")
                offlineLlm.initialize()
                readiness.value = ReadyState(true, offlineLlm.status)
            } catch (error: Exception) {
                readiness.value = ReadyState(true, "Ein Teil konnte nicht geladen werden. Texteingabe ist verfügbar.")
            }
        }
    }

    suspend fun reloadDocuments(): List<String> = documentMutex.withLock {
        pdfIngestor.indexAllDocuments(vectorStore)
    }
    fun isSmartSearchActive(): Boolean = embeddingProvider.isAvailable
}
