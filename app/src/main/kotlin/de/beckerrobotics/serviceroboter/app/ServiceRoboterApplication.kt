package de.beckerrobotics.serviceroboter.app

import android.app.Application
import de.beckerrobotics.serviceroboter.app.llm.GeminiNanoLlmEngine
import de.beckerrobotics.serviceroboter.app.llm.TemplateOfflineLanguageModel
import de.beckerrobotics.serviceroboter.app.network.SimpleOnlineFallbackClient
import de.beckerrobotics.serviceroboter.app.rag.OnnxEmbeddingProvider
import de.beckerrobotics.serviceroboter.app.rag.PdfIngestor
import de.beckerrobotics.serviceroboter.app.stt.VoskSttEngine
import de.beckerrobotics.serviceroboter.core.DefaultPrivacyFilter
import de.beckerrobotics.serviceroboter.core.InMemoryVectorStore
import de.beckerrobotics.serviceroboter.core.OfflineLanguageModel
import de.beckerrobotics.serviceroboter.core.RuleBasedIntentEngine
import de.beckerrobotics.serviceroboter.core.ServiceRoboterPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manuelle, bewusst einfache Zusammensetzung ("Dependency Injection") aller Bausteine aus der
 * Recherche. Für ein wachsendes Projekt bietet sich später ein DI-Framework an (z. B. Hilt) –
 * für den Prototyp reicht das hier, um die Verdrahtung nachvollziehbar zu halten.
 *
 * [offlineLlm] verwendet standardmäßig [TemplateOfflineLanguageModel] (sofort lauffähig ohne
 * Gerätevoraussetzungen). Sobald [GeminiNanoLlmEngine] auf einem unterstützten Gerät getestet und
 * an die aktuelle ML-Kit-GenAI-API angepasst wurde (siehe Kommentare dort), hier einfach tauschen.
 */
class ServiceRoboterApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var sttEngine: VoskSttEngine
        private set

    lateinit var vectorStore: InMemoryVectorStore
        private set

    lateinit var pipeline: ServiceRoboterPipeline
        private set

    lateinit var pdfIngestor: PdfIngestor
        private set

    private lateinit var embeddingProvider: OnnxEmbeddingProvider

    var isReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()

        embeddingProvider = OnnxEmbeddingProvider(this)
        vectorStore = InMemoryVectorStore(embeddingProvider)
        pdfIngestor = PdfIngestor(this)

        sttEngine = VoskSttEngine(this)

        val intentEngine = RuleBasedIntentEngine(RuleBasedIntentEngine.defaultIntents())

        // Standardmäßig der sofort funktionsfähige Platzhalter (siehe Klassenkommentar oben).
        // Alternative: GeminiNanoLlmEngine(this) - siehe README "Nächste Schritte".
        val offlineLlm: OfflineLanguageModel = TemplateOfflineLanguageModel()

        val onlineFallback = SimpleOnlineFallbackClient(
            context = this,
            apiKey = GEMINI_API_KEY.ifBlank { null }
        )

        val privacyFilter = DefaultPrivacyFilter()

        pipeline = ServiceRoboterPipeline(
            intentEngine = intentEngine,
            knowledgeBase = vectorStore,
            offlineLlm = offlineLlm,
            onlineFallback = onlineFallback,
            privacyFilter = privacyFilter
        )

        applicationScope.launch {
            sttEngine.initialize()
            reloadDocuments()
            isReady = true
        }
    }

    suspend fun reloadDocuments() {
        vectorStore.clear()
        pdfIngestor.indexAllDocuments(vectorStore)
    }

    fun isSmartSearchActive(): Boolean = embeddingProvider.isAvailable

    companion object {
        /** TODO: Hier deinen Google Gemini API Key eintragen für Online-KI-Antworten.
         *  Kostenlos erstellbar unter: https://aistudio.google.com/app/apikey */
        const val GEMINI_API_KEY = "AIzaSyBWJyMomybKnsiYD7aXV5AruXxXmG1bxr8"
    }
}
