package de.beckerrobotics.serviceroboter.core

/**
 * Datenschutz-Leitplanke für die Online-Fallback-Stufe (Stufe 3, niedrigste Priorität).
 *
 * Setzt die im Recherche-Dokument (Abschnitt 5 und 6) beschriebenen Grundsätze technisch um:
 * bevor irgendeine Anfrage das Gerät verlassen darf, wird geprüft, ob sie Hinweise auf
 * besondere personenbezogene Daten (Art. 9 DSGVO: Gesundheit, Pflege/Betreuung) oder
 * offensichtliche Identifikatoren (E-Mail, Telefonnummer) enthält. Trifft das zu, wird die
 * Anfrage blockiert statt online gesendet zu werden.
 *
 * Das ist bewusst konservativ (lieber einmal zu viel blockieren als einmal zu wenig) und ersetzt
 * keine rechtliche Prüfung – dient als technische Grundabsicherung, die im Projekt mit
 * Betreuungspersonal/Datenschutzbeauftragten abgestimmt werden sollte (siehe README, "Offene Punkte").
 */
class DefaultPrivacyFilter(
    private val additionalSensitiveTerms: List<String> = emptyList()
) : PrivacyFilter {

    private val sensitiveHealthTerms = listOf(
        "diagnose", "medikament", "tablette", "krankheit", "demenz", "alzheimer",
        "pflegegrad", "krankenkasse", "arzt", "therapie", "beeintraechtigung",
        "behinderung", "depression", "schmerzen", "blutdruck", "insulin"
    )

    private val emailPattern = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val phonePattern = Regex("(\\+?\\d[\\d\\s/-]{6,}\\d)")

    override fun sanitize(text: String): SanitizedQuery {
        val normalized = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")

        if (emailPattern.containsMatchIn(text)) {
            return SanitizedQuery(text, blocked = true, reason = "Enthält eine E-Mail-Adresse")
        }
        if (phonePattern.containsMatchIn(text)) {
            return SanitizedQuery(text, blocked = true, reason = "Enthält eine mögliche Telefonnummer")
        }

        val allSensitiveTerms = sensitiveHealthTerms + additionalSensitiveTerms.map { it.lowercase() }
        val matchedTerm = allSensitiveTerms.firstOrNull { normalized.contains(it) }
        if (matchedTerm != null) {
            return SanitizedQuery(
                text,
                blocked = true,
                reason = "Enthält einen gesundheits-/betreuungsbezogenen Begriff (\"$matchedTerm\") " +
                    "– besondere Kategorie personenbezogener Daten nach Art. 9 DSGVO, wird nicht online gesendet."
            )
        }

        return SanitizedQuery(text, blocked = false)
    }
}
