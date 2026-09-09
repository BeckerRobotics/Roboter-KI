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
    private val context: Context,
    private val sampleRateHint: Float = 16000f
) : SpeechToTextEngine {

    private var model: Model? = null

    override val isReady: Boolean get() = model != null

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        // Wir suchen im gesamten App-Speicher nach dem Vosk-Ordner
        val internalFilesDir = context.filesDir
        val externalFilesDir = context.getExternalFilesDir(null)
        
        val actualPath = findModelDir(internalFilesDir) ?: externalFilesDir?.let { findModelDir(it) }

        android.util.Log.d("VoskSttEngine", "Suche Modell in intern: ${internalFilesDir.absolutePath} und extern: ${externalFilesDir?.absolutePath ?: "n/a"}")
        
        if (actualPath == null) {
            android.util.Log.e("VoskSttEngine", "Kein gültiges Vosk-Modell gefunden (suche nach 'am'-Ordner)")
            return@withContext false
        }

        android.util.Log.i("VoskSttEngine", "Lade Modell von: $actualPath")
        runCatching {
            model = Model(actualPath)
        }.onFailure {
            android.util.Log.e("VoskSttEngine", "Fehler beim Initialisieren des Vosk-Modells: ${it.message}")
        }.isSuccess
    }

    /** Findet den Ordner, der direkt die Vosk-Daten (am, conf, etc.) enthält. */
    private fun findModelDir(dir: File): String? {
        if (!dir.exists()) return null
        if (File(dir, "am").exists() && File(dir, "conf").exists()) return dir.absolutePath
        
        return dir.listFiles()?.firstNotNullOfOrNull { 
            if (it.isDirectory) findModelDir(it) else null 
        }
    }

    override suspend fun transcribe(audioSamples: ShortArray, sampleRate: Int): TranscriptionResult =
        withContext(Dispatchers.Default) {
            android.util.Log.d("VoskSttEngine", "Transkription gestartet... Samples: ${audioSamples.size}")
            val currentModel = model
                ?: run {
                    android.util.Log.e("VoskSttEngine", "Transkription abgebrochen: Modell nicht geladen!")
                    return@withContext TranscriptionResult(text = "", confidence = 0f)
                }

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
                android.util.Log.d("VoskSttEngine", "Vosk Ergebnis JSON: $resultJson")

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
