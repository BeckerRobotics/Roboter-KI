package de.beckerrobotics.serviceroboter.app.tts

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import de.beckerrobotics.serviceroboter.app.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

class TtsProvider(private val context: Context, private val settings: AppSettings) {
    private var tts: TextToSpeech? = null
    val status = MutableStateFlow("Deutsche Stimme wird geprüft.")
    val speaking = MutableStateFlow(false)
    private var initialized = false
    init { initialize() }

    fun initialize() {
        stop()
        tts?.shutdown()
        tts = null
        initialized = false
        val engine = context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0
        ).map { it.serviceInfo.packageName }.distinct()
            .firstOrNull { it.startsWith("com.k2fsa.sherpa") }
        if (engine == null) {
            status.value = "Bitte das mitgelieferte Thorsten-Sprachpaket installieren."
            return
        }
        tts = TextToSpeech(context, { result ->
            val active = tts
            if (result != TextToSpeech.SUCCESS || active == null ||
                active.setLanguage(Locale.GERMAN) < TextToSpeech.LANG_AVAILABLE) {
                status.value = "Die deutsche Stimme konnte nicht gestartet werden."
            } else {
                // Sherpa's Android service uses locale names for its offline voices.
                // Refuse a fallback to Google's model-specific voices after a binding failure.
                val voice = active.voices.orEmpty().filter {
                    it.locale.language == "de" && !it.isNetworkConnectionRequired &&
                        it.name == it.locale.toLanguageTag()
                }.sortedBy { if (it.locale.country == "DE") 0 else 1 }.firstOrNull()
                if (voice == null || active.setVoice(voice) != TextToSpeech.SUCCESS) {
                    status.value = "Thorsten ist nicht verfügbar. Bitte das Sprachpaket einmal öffnen."
                } else {
                    active.setSpeechRate(settings.speechRate)
                    active.setPitch(1.0f)
                    initialized = true
                    status.value = "Deutsche Offline-Stimme bereit"
                    active.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { speaking.value = true }
                        override fun onDone(id: String?) { speaking.value = false }
                        @Deprecated("Android API") override fun onError(id: String?) {
                            speaking.value = false
                            status.value = "Vorlesen fehlgeschlagen. Die Antwort bleibt lesbar."
                        }
                        override fun onStop(id: String?, interrupted: Boolean) { speaking.value = false }
                    })
                }
            }
        }, engine)
    }
    fun speak(text: String) {
        if (!initialized || text.isBlank()) return
        tts?.setSpeechRate(settings.speechRate)
        val clean = text.replace(Regex("https?://\\S+"), "")
            .replace(Regex("[#*_]"), "").trim()
        val parts = clean.split(Regex("(?<=[.!?])\\s+")).flatMap { it.chunked(500) }.filter { it.isNotBlank() }
        parts.forEachIndexed { i, part ->
            val result = tts?.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "antwort_$i")
            if (result == TextToSpeech.ERROR) status.value = "Vorlesen fehlgeschlagen."
        }
    }
    fun stop() { tts?.stop(); speaking.value = false }
    fun release() { stop(); tts?.shutdown(); tts = null; initialized = false }
}
