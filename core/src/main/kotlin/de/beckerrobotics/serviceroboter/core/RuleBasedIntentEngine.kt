package de.beckerrobotics.serviceroboter.core

import kotlin.math.max

/**
 * Definition eines erkennbaren Befehls ("Intent").
 *
 * @param name eindeutiger Bezeichner, z. B. "uebung_starten"
 * @param triggerPhrases typische Formulierungen, die zu diesem Intent gehören (mehrere Varianten
 *        erhöhen die Robustheit gegenüber freier Formulierung – das ist der Unterschied zu starrem
 *        Keyword-Matching: es wird nicht auf exakte Übereinstimmung geprüft, sondern auf Wort-Überlappung).
 * @param slotPattern optionales Regex mit benannten Gruppen zur Extraktion von Parametern
 *        (z. B. Uhrzeit bei "erinnerung_stellen"). Regex arbeitet auf dem normalisierten Text.
 */
data class IntentDefinition(
    val name: String,
    val triggerPhrases: List<String>,
    val slotPattern: Regex? = null
)

/**
 * Offline arbeitender Intent-Klassifikator auf Basis von Wort-Überlappung (Jaccard-Ähnlichkeit)
 * zwischen der Nutzeräußerung und hinterlegten Beispielformulierungen pro Intent.
 *
 * Das ist bewusst kein starres "exaktes Wort"-Matching: unterschiedliche Formulierungen mit
 * denselben Kernbegriffen ("Starte die Übung" / "Ich möchte eine Übung machen" / "lass uns
 * eine Aufgabe starten") werden demselben Intent zugeordnet, solange genug inhaltliche
 * Überschneidung besteht.
 *
 * Dient als schneller, ressourcenschonender erster Schritt der Pipeline für klar definierte
 * Befehle (siehe Recherche-Dokument, Abschnitt 3.1 – Pendant zu Picovoice Rhino, falls kein
 * kommerzielles SDK/AccessKey genutzt werden soll).
 */
class RuleBasedIntentEngine(
    private val intents: List<IntentDefinition>,
    private val minConfidence: Float = 0.34f
) : IntentEngine {

    override suspend fun classify(text: String): IntentResult {
        val inputTokens = tokenize(text)
        if (inputTokens.isEmpty()) return IntentResult(null, confidence = 0f)

        var bestIntent: IntentDefinition? = null
        var bestScore = 0f

        for (intent in intents) {
            val score = intent.triggerPhrases
                .map { jaccard(inputTokens, tokenize(it)) }
                .maxOrNull() ?: 0f
            if (score > bestScore) {
                bestScore = score
                bestIntent = intent
            }
        }

        if (bestIntent == null || bestScore < minConfidence) {
            return IntentResult(null, confidence = bestScore)
        }

        val slots = bestIntent.slotPattern
            ?.let { pattern -> extractNamedGroups(pattern, normalize(text)) }
            ?: emptyMap()

        return IntentResult(bestIntent.name, slots, bestScore)
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toFloat() / max(union, 1)
    }

    private fun tokenize(text: String): Set<String> =
        normalize(text).split(Regex("\\s+")).filter { it.length > 1 }.toSet()

    private fun normalize(text: String): String =
        text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()

    private fun extractNamedGroups(pattern: Regex, input: String): Map<String, String> {
        val match = pattern.find(input) ?: return emptyMap()
        val names = Regex("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").findAll(pattern.pattern)
            .map { it.groupValues[1] }
            .toList()
        return names.mapNotNull { name ->
            runCatching { match.groups[name]?.value }.getOrNull()?.let { name to it }
        }.toMap()
    }

    companion object {
        /**
         * Beispielhafte Standard-Intents für die Lernanwendung (Gedächtnistraining etc.).
         * Bewusst als Ausgangspunkt gedacht – in der App-Konfiguration erweiterbar/anpassbar,
         * ohne Code ändern zu müssen (z. B. später aus einer JSON/Room-Konfiguration ladbar).
         */
        fun defaultIntents(): List<IntentDefinition> = listOf(
            IntentDefinition(
                name = "uebung_starten",
                triggerPhrases = listOf(
                    "starte die uebung", "ich moechte eine uebung machen",
                    "lass uns eine aufgabe starten", "wir koennen anfangen zu trainieren",
                    "beginne das training", "ich will jetzt spielen"
                )
            ),
            IntentDefinition(
                name = "erinnerung_stellen",
                // Hinweis: triggerPhrases enthalten bewusst nur natürliche Sprache (kein Regex) –
                // sie dienen der Jaccard-Ähnlichkeitsberechnung. Die eigentliche Parameter-Extraktion
                // (Uhrzeit/Inhalt) übernimmt separat das slotPattern unten.
                triggerPhrases = listOf(
                    "erinnere mich um sieben uhr an die verabredung",
                    "stelle eine erinnerung um acht uhr",
                    "ich moechte eine erinnerung"
                ),
                slotPattern = Regex(
                    "erinnere mich um (?<uhrzeit>\\d{1,2}(:\\d{2})?( uhr)?) an (?<inhalt>.+)"
                )
            ),
            IntentDefinition(
                name = "frage_dokument",
                triggerPhrases = listOf(
                    "was steht in dem dokument", "kannst du mir das memo erklaeren",
                    "was sagt die pdf dazu", "schau mal im dokument nach",
                    "was habe ich dazu notiert"
                )
            ),
            IntentDefinition(
                name = "wiederholen",
                triggerPhrases = listOf(
                    "kannst du das wiederholen", "sag das nochmal", "ich habe das nicht verstanden",
                    "bitte nochmal"
                )
            ),
            IntentDefinition(
                name = "abbrechen",
                triggerPhrases = listOf(
                    "stopp", "hoer auf", "brich ab", "ich will nicht mehr", "beende das"
                )
            ),
            IntentDefinition(
                name = "hilfe",
                triggerPhrases = listOf(
                    "hilfe", "was kannst du", "wie funktioniert das", "ich brauche hilfe"
                )
            )
        )
    }
}
