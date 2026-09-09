package de.beckerrobotics.serviceroboter.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.beckerrobotics.serviceroboter.app.stt.AudioCapture
import de.beckerrobotics.serviceroboter.core.AnswerSource
import de.beckerrobotics.serviceroboter.core.PipelineStage
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val currentStage: String? = null,
    val transcript: String = "",
    val answer: String = "",
    val answerSource: AnswerSource? = null,
    val documentCount: Int = 0,
    val isSmartSearchActive: Boolean = false
)

/**
 * Verbindet die UI mit [ServiceRoboterApplication.pipeline]. Zeigt bewusst an, welche Stufe
 * geantwortet hat (currentStage/answerSource) – das dient der im Recherche-Dokument
 * (Abschnitt 6) geforderten Transparenz gegenüber den Nutzer:innen, insbesondere wenn die
 * Online-Fallback-Stufe verwendet wurde ("Ich schaue online nach...").
 */
class MainViewModel(private val app: ServiceRoboterApplication) : ViewModel() {

    private val audioCapture = AudioCapture()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        updateStatus()
    }

    private fun updateStatus() {
        _uiState.value = _uiState.value.copy(
            documentCount = app.vectorStore.documentCount,
            isSmartSearchActive = app.isSmartSearchActive()
        )
    }

    fun importPdf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contentResolver = app.contentResolver
            val fileName = "imported_${System.currentTimeMillis()}.pdf"
            val targetFile = File(File(app.getExternalFilesDir(null), "memos"), fileName)
            
            if (!targetFile.parentFile!!.exists()) targetFile.parentFile!!.mkdirs()

            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            app.reloadDocuments()
            updateStatus()
        }
    }

    fun startListening() {
        if (_uiState.value.isListening) return
        _uiState.value = _uiState.value.copy(isListening = true, transcript = "", answer = "")

        viewModelScope.launch {
            val samples = audioCapture.recordFixedDuration()
            val transcription = app.sttEngine.transcribe(samples, sampleRate = 16000)
            _uiState.value = _uiState.value.copy(isListening = false, transcript = transcription.text)

            if (transcription.text.isNotBlank()) {
                handleUtterance(transcription.text)
            }
        }
    }

    /** Ermöglicht das Testen der Pipeline auch per Texteingabe, ohne Mikrofon/Vosk-Modell. */
    fun submitTypedText(text: String) {
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(transcript = text, answer = "")
        viewModelScope.launch { handleUtterance(text) }
    }

    private suspend fun handleUtterance(text: String) {
        _uiState.value = _uiState.value.copy(isThinking = true)

        val result = app.pipeline.handle(text) { stage ->
            val label = when (stage) {
                is PipelineStage.IntentCheck -> "Prüfe bekannte Befehle …"
                is PipelineStage.KnowledgeBaseLookup -> "Schaue in deinen Dokumenten nach …"
                is PipelineStage.OfflineLlmGeneration -> "Denke selbst nach (offline) …"
                is PipelineStage.OnlineFallback -> "Ich schaue online nach …"
                is PipelineStage.Blocked -> "Aus Datenschutzgründen wird nicht online gesucht (${stage.reason})."
            }
            _uiState.value = _uiState.value.copy(currentStage = label)
        }

        _uiState.value = _uiState.value.copy(
            isThinking = false,
            currentStage = null,
            answer = result.text,
            answerSource = result.source
        )
    }
}
