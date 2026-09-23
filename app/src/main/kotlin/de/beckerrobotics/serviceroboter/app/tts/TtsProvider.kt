package de.beckerrobotics.serviceroboter.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Kapselt die Android Text-To-Speech (TTS) Funktionalität.
 * Ermöglicht es dem Roboter, Textantworten laut vorzulesen.
 */
class TtsProvider(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.GERMAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsProvider", "Sprache Deutsch wird nicht unterstützt oder Daten fehlen.")
            } else {
                isInitialized = true
                Log.i("TtsProvider", "TTS erfolgreich initialisiert.")
            }
        } else {
            Log.e("TtsProvider", "TTS Initialisierung fehlgeschlagen.")
        }
    }

    fun speak(text: String) {
        if (isInitialized && text.isNotBlank()) {
            Log.d("TtsProvider", "Spreche: $text")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "serviceroboter_msg")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
