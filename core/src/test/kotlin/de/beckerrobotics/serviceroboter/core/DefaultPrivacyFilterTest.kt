package de.beckerrobotics.serviceroboter.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultPrivacyFilterTest {

    private val filter = DefaultPrivacyFilter()

    @Test
    fun `blockiert Anfragen mit Gesundheitsbezug`() {
        val result = filter.sanitize("Welche Nebenwirkungen hat mein Medikament?")
        assertTrue(result.blocked)
    }

    @Test
    fun `blockiert Anfragen mit E-Mail-Adresse`() {
        val result = filter.sanitize("Schick das an oma.erna@beispiel.de")
        assertTrue(result.blocked)
    }

    @Test
    fun `blockiert Anfragen mit Telefonnummer`() {
        val result = filter.sanitize("Ruf mich unter 0151 23456789 an")
        assertTrue(result.blocked)
    }

    @Test
    fun `laesst unbedenkliche allgemeine Fragen durch`() {
        val result = filter.sanitize("Wie wird das Wetter morgen in Berlin?")
        assertFalse(result.blocked)
    }
}
