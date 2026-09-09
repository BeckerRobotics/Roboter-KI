package de.beckerrobotics.serviceroboter.app.stt

import android.content.Context
import de.beckerrobotics.serviceroboter.core.SpeechToTextEngine
import de.beckerrobotics.serviceroboter.core.TranscriptionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline-Spracherkennung auf Basis von Vosk (siehe Recherche-Dokument, Abschnitt 2).
 *
 * WICHTIG – vor dem ersten Start zu erledigen (siehe README.md):
 *  1. Ein deutsches Vosk-Modell herunterladen (z. B. "vosk-model-small-de-0.15" von
 *     https://alphacephei.com/vosk/models für schnelle Antworten auf begrenzter Hardware,
 *     oder ein größeres Modell für höhere Genauigkeit).
 *  2. Das entpackte Modellverzeichnis auf das Gerät bringen (z. B. unter
 *     `context.filesDir/vosk-model-de` oder per `adb push`) – Modelle sind zu groß, um sie
 *     sinnvoll in die APK zu bündeln.
 *  3. [modelPath] entsprechend setzen.
 *
 * Die eigentliche Audioaufnahme (AudioRecord) erfolgt in [de.beckerrobotics.serviceroboter.app.ui.VoiceCaptureController]
 * bzw. der aufrufenden UI-Schicht – dieser Wrapper kümmert sich nur um die Umwandlung von
 * bereits aufgenommenen PCM-Samples in Text, passend zum core-Interface [SpeechToTextEngine].
 */
class VoskSttEngine(
    context: Context,
    private val modelPath: String = File(context.filesDir, "vosk-model-de").absolutePath,
    private val sampleRateHint: Float = 16000f
) : SpeechToTextEngine {

    private var model: Model? = null

    override val isReady: Boolean get() = model != null

    /**
     * Lädt das Vosk-Modell. Sollte einmalig beim App-Start (z. B. in [de.beckerrobotics.serviceroboter.app.ServiceRoboterApplication])
     * in einem Hintergrund-Dispatcher aufgerufen werden – das Laden kann je nach Modellgröße
     * spürbar Zeit in Anspruch nehmen.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (!File(modelPath).exists()) {
            // Bewusst kein Absturz: die App soll auch ohne Modell startbar sein (z. B. für
            // Entwicklung der RAG-/Wissensbasis-Teile), STT ist dann einfach nicht verfügbar.
            return@withContext false
        }
        runCatching {
            model = Model(modelPath)
        }.isSuccess
    }

    override suspend fun transcribe(audioSamples: ShortArray, sampleRate: Int): TranscriptionResult =
        withContext(Dispatchers.Default) {
            val currentModel = model
                ?: return@withContext TranscriptionResult(text = "", confidence = 0f)

            val recognizer = Recognizer(currentModel, sampleRate.toFloat())
            try {
                // Vosk erwartet Little-Endian PCM16-Bytes.
                val bytes = ByteArray(audioSamples.size * 2)
                for (i in audioSamples.indices) {
                    val sample = audioSamples[i].toInt()
                    bytes[i * 2] = (sample and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                }
                recognizer.acceptWaveForm(bytes, bytes.size)
                val resultJson = recognizer.finalResult

                // Vosk liefert JSON der Form {"text": "..."}. Kein extra JSON-Parser-Dependency
                // eingeführt, um die App schlank zu halten – bei Bedarf gerne gegen org.json o.ä. tauschen.
                val text = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
                    .find(resultJson)?.groupValues?.get(1)
                    ?.trim()
                    ?: ""

                // Vosk liefert im Standard-Setup keine Gesamt-Konfidenz mit; wir behelfen uns mit
                // einer einfachen Heuristik (nicht-leerer Text -> hohe Konfidenz). Für differenziertere
                // Konfidenzwerte kann Vosk mit `SetWords(true)` pro-Wort-Konfidenzen liefern.
                TranscriptionResult(text = text, confidence = if (text.isNotBlank()) 0.9f else 0f)
            } finally {
                recognizer.close()
            }
        }

    fun release() {
        model?.close()
        model = null
    }
}
