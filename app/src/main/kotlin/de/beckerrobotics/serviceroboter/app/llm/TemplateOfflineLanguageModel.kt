package de.beckerrobotics.serviceroboter.app.llm

import de.beckerrobotics.serviceroboter.core.GenerationResult
import de.beckerrobotics.serviceroboter.core.KnowledgeHit
import de.beckerrobotics.serviceroboter.core.OfflineLanguageModel

/**
 * Sofort einsatzbereiter Platzhalter für [OfflineLanguageModel], der KEIN echtes KI-Modell
 * benötigt (kein Modell-Download, kein AICore-taugliches Gerät nötig).
 *
 * Sinn dieser Klasse: den Prototyp von Anfang an vollständig durchspielbar zu machen –
 * inklusive Stufe 1 (Wissensbasis, hier: Kontext wird einfach direkt wiedergegeben) – auch
 * bevor [GeminiNanoLlmEngine] fertig angebunden bzw. auf einem unterstützten Gerät getestet ist.
 *
 * Zum Testen der "echten" freien Sprachverständnis-Stufe (Stufe 2) diese Implementierung
 * gegen [GeminiNanoLlmEngine] (oder ein LiteRT-LM-basiertes Pendant) austauschen – siehe
 * README.md, Abschnitt "Nächste Schritte".
 */
class TemplateOfflineLanguageModel : OfflineLanguageModel {

    override val isAvailable: Boolean = true

    override suspend fun generate(prompt: String, context: List<KnowledgeHit>): GenerationResult {
        if (context.isNotEmpty()) {
            val best = context.first()
            return GenerationResult(
                text = best.chunkText,
                confidence = 0.6f
            )
        }
        // Kein Kontext vorhanden (Stufe 2: freies Sprachverständnis) – ohne echtes Sprachmodell
        // kann hier ehrlicherweise keine sinnvolle freie Antwort erzeugt werden. Bewusst
        // confidence = 0f, damit die Pipeline sauber zur nächsten Stufe (Online-Fallback,
        // falls erlaubt) weitergeht, statt eine Nicht-Antwort als Treffer auszugeben.
        return GenerationResult(text = "", confidence = 0f)
    }
}
