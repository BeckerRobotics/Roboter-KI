package de.beckerrobotics.serviceroboter.app.llm

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.generationConfig
import de.beckerrobotics.serviceroboter.core.GenerationResult
import de.beckerrobotics.serviceroboter.core.KnowledgeHit
import de.beckerrobotics.serviceroboter.core.OfflineLanguageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-Device-Sprachmodell über Android AICore / Gemini Nano.
 */
class GeminiNanoLlmEngine(private val context: Context) : OfflineLanguageModel {

    // Konfiguration für das lokale Modell
    private val generativeModel by lazy {
        GenerativeModel(
            modelName = "gemini-nano", // Weist das SDK an, AICore/Gemini Nano zu nutzen
            apiKey = "unused-for-nano", // Für lokales Nano oft nicht benötigt, aber vom SDK-Interface verlangt
            generationConfig = generationConfig {
                temperature = 0.1f // Niedrig für stabilere, faktenbasierte Antworten
                topK = 40
                topP = 0.95f
            },
            safetySettings = listOf(
                SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.MEDIUM_AND_ABOVE),
                SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.MEDIUM_AND_ABOVE)
            )
        )
    }

    private var availabilityChecked = false
    private var available = false

    override val isAvailable: Boolean
        get() {
            if (!availabilityChecked) {
                available = checkAvailabilityBlocking()
                availabilityChecked = true
            }
            return available
        }

    private fun checkAvailabilityBlocking(): Boolean = runCatching {
        // In einer echten Umgebung prüfen wir hier, ob das Gerät die Gemini Nano Voraussetzungen erfüllt.
        // Aktuell geschieht dies oft durch einen Versuch, das Modell zu instanziieren oder über 
        // spezifische Hardware-Checks. Für den Prototyp setzen wir auf "true", wenn das SDK geladen werden kann.
        true 
    }.getOrDefault(false)

    override suspend fun generate(prompt: String, context: List<KnowledgeHit>): GenerationResult =
        withContext(Dispatchers.Default) {
            if (!isAvailable) {
                return@withContext GenerationResult(text = "", confidence = 0f)
            }

            val augmentedPrompt = buildPrompt(prompt, context)

            runCatching {
                val response = generativeModel.generateContent(augmentedPrompt)
                val responseText = response.text
                
                if (!responseText.isNullOrBlank()) {
                    GenerationResult(text = responseText, confidence = 0.8f)
                } else {
                    GenerationResult(text = "", confidence = 0f)
                }
            }.getOrElse {
                GenerationResult(text = "", confidence = 0f)
            }
        }

    private fun buildPrompt(userQuestion: String, context: List<KnowledgeHit>): String {
        if (context.isEmpty()) return userQuestion
        val contextText = context.joinToString("\n---\n") { it.chunkText }
        return """
            Beantworte die folgende Frage kurz, freundlich und in einfacher Sprache anhand des
            gegebenen Kontexts. Wenn die Antwort nicht im Kontext steht, sag das ehrlich.

            Kontext:
            $contextText

            Frage: $userQuestion
        """.trimIndent()
    }
}
