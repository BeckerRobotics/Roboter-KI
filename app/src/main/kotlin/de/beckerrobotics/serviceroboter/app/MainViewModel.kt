package de.beckerrobotics.serviceroboter.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.beckerrobotics.serviceroboter.app.stt.AudioCapture
import de.beckerrobotics.serviceroboter.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

data class UiState(
    val ready: Boolean = false, val busy: Boolean = false, val listening: Boolean = false,
    val stage: String = "Der Roboter wird vorbereitet.", val transcript: String = "",
    val answer: String = "", val source: AnswerSource? = null, val citations: List<String> = emptyList(),
    val documentCount: Int = 0, val documentNames: List<String> = emptyList(),
    val llmStatus: String = "", val searchStatus: String = "", val speechStatus: String = "",
    val sttReady: Boolean = false, val onlineEnabled: Boolean = true,
    val searchEndpoint: String = "", val speechRate: Float = 0.9f,
    val message: String = ""
)

class MainViewModel(private val app: ServiceRoboterApplication) : ViewModel() {
    private val recorder = AudioCapture()
    private val state = MutableStateFlow(UiState())
    val uiState = state.asStateFlow()
    private var activeJob: Job? = null
    private var lastSpoken = ""

    init {
        viewModelScope.launch {
            app.readiness.collect { readiness ->
                state.update { it.copy(ready = readiness.ready, stage = if (readiness.ready) "" else readiness.message) }
                refresh()
            }
        }
        viewModelScope.launch { app.ttsProvider.status.collect { status ->
            state.update { it.copy(speechStatus = status) }
        } }
    }

    private fun refresh() {
        state.update { it.copy(
            documentCount = app.vectorStore.documentCount,
            documentNames = app.pdfIngestor.documentsDir.listFiles().orEmpty()
                .filter { f -> f.extension in setOf("pdf", "txt", "md") }.map { f -> f.name }.sorted(),
            llmStatus = app.offlineLlm.status, searchStatus = app.embeddingProvider.modelName,
            sttReady = app.sttEngine.isReady, onlineEnabled = app.settings.onlineEnabled,
            searchEndpoint = app.settings.searchEndpoint, speechRate = app.settings.speechRate
        ) }
    }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy || !state.value.ready) return
        app.ttsProvider.stop()
        state.update { it.copy(busy = true, message = "") }
        activeJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { state.update { it.copy(message = error.message ?: "Das hat leider nicht funktioniert.") } }
            finally { state.update { it.copy(busy = false, listening = false, stage = "") }; refresh() }
        }
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        work { handle(text.take(2000)) }
    }
    fun listen() = work {
        if (!app.sttEngine.isReady) error("Spracherkennung ist noch nicht bereit.")
        state.update { it.copy(listening = true, stage = "Ich höre zu. Sprechen Sie in Ruhe.") }
        val samples = recorder.recordUntilSilence()
        state.update { it.copy(listening = false, stage = "Ich verarbeite Ihre Frage.") }
        val transcript = app.sttEngine.transcribe(samples, 16000)
        if (transcript.text.isBlank()) error("Ich habe Sie leider nicht verstanden. Versuchen Sie es bitte noch einmal.")
        handle(transcript.text)
    }
    private suspend fun handle(text: String) {
        state.update { it.copy(transcript = text, answer = "", citations = emptyList(), source = null) }
        val result = app.pipeline.handle(text) { stage ->
            state.update { it.copy(stage = when (stage) {
                PipelineStage.IntentCheck -> "Ich verarbeite Ihre Frage."
                PipelineStage.KnowledgeBaseLookup -> "Ich schaue in Ihren Dokumenten nach."
                PipelineStage.OfflineLlmGeneration -> "Ich formuliere eine Antwort."
                PipelineStage.OnlineFallback -> "Ich suche jetzt im Internet."
                is PipelineStage.Blocked -> "Ihre Angaben bleiben auf dem Roboter."
            }) }
        }
        val spoken = if (result.intent?.intentName == "wiederholen")
            lastSpoken.ifBlank { "Es gibt noch keine vorherige Antwort." } else result.text
        if (result.intent?.intentName != "wiederholen" && result.intent?.intentName != "abbrechen") lastSpoken = spoken
        state.update { it.copy(
            answer = spoken, source = result.source,
            citations = result.knowledgeHits.map { h -> h.sourceId + (h.pageNumber?.let { page -> ", Seite $page" } ?: "") }
                .distinct() + result.webSources.map { s -> s.title + "\n" + s.url }
        ) }
        if (result.intent?.intentName != "abbrechen") app.ttsProvider.speak(spoken)
    }

    fun stop() {
        recorder.stop()
        app.offlineLlm.cancel()
        activeJob?.cancel()
        app.ttsProvider.stop()
    }
    fun repeat() { if (lastSpoken.isNotBlank()) app.ttsProvider.speak(lastSpoken) }
    fun importDocument(uri: Uri) = work {
        state.update { it.copy(stage = "Das Dokument wird eingelesen.") }
        val name = app.pdfIngestor.importDocument(uri)
        val warnings = app.reloadDocuments()
        state.update { it.copy(message = "Gespeichert: $name" + if (warnings.isEmpty()) "" else "\n" + warnings.joinToString("\n")) }
    }
    fun saveMemo(title: String, text: String) = work {
        app.pdfIngestor.saveMemo(title, text)
        app.reloadDocuments()
        state.update { it.copy(message = "Ihr Memo wurde gespeichert.") }
    }
    fun deleteDocument(name: String) = work {
        withContext(Dispatchers.IO) {
            val directory = app.pdfIngestor.documentsDir.canonicalFile
            val file = File(directory, name).canonicalFile
            require(file.parentFile == directory)
            check(file.delete()) { "Das Dokument konnte nicht gelöscht werden." }
            app.vectorStore.removeDocument(name)
        }
        state.update { it.copy(message = "Das Dokument wurde gelöscht.") }
    }
    fun importModel(uri: Uri) = work {
        state.update { it.copy(stage = "Das Sprachmodell wird auf dem Roboter gespeichert.") }
        withContext(Dispatchers.IO) {
            val target = app.offlineLlm.modelFile
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, "assistant.gguf.partial")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(4)
                    java.io.DataInputStream(input).readFully(magic)
                    check(magic.toString(Charsets.US_ASCII) == "GGUF") {
                        "Bitte eine gültige GGUF-Modelldatei auswählen."
                    }
                    partial.outputStream().use { output ->
                        output.write(magic)
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 4L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= 6L * 1024 * 1024 * 1024) { "Das Modell ist größer als 6 GB." }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("Modell konnte nicht geöffnet werden.")
                // Linux rename replaces the file atomically; existing mmap remains valid until reinitialization.
                check(partial.renameTo(target)) { "Modell konnte nicht gespeichert werden." }
            } finally { partial.delete() }
        }
        app.offlineLlm.initialize()
        state.update { it.copy(message = app.offlineLlm.status) }
    }
    fun saveSettings(online: Boolean, endpoint: String, rate: Float) {
        if (endpoint.isNotBlank()) {
            val parsed = endpoint.toHttpUrlOrNull()
            if (parsed?.isHttps != true || parsed.username.isNotBlank() || parsed.password.isNotBlank()) {
                state.update { it.copy(message = "Bitte eine HTTPS-Suchadresse ohne Zugangsdaten eingeben.") }
                return
            }
        }
        app.settings.onlineEnabled = online
        app.settings.searchEndpoint = endpoint
        app.settings.speechRate = rate
        refresh()
        state.update { it.copy(message = "Einstellungen gespeichert.") }
    }
    fun refreshVoice() { app.ttsProvider.initialize() }
    override fun onCleared() { stop(); super.onCleared() }
}
