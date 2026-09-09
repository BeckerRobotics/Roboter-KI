package de.beckerrobotics.serviceroboter.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleBasedIntentEngineTest {

    private val engine = RuleBasedIntentEngine(RuleBasedIntentEngine.defaultIntents())

    @Test
    fun `erkennt Uebung starten auch bei abweichender Formulierung`() = runTest {
        val result = engine.classify("Ich möchte gerne eine Übung machen")
        assertEquals("uebung_starten", result.intentName)
        assertTrue(result.confidence > 0.3f)
    }

    @Test
    fun `erkennt keinen Intent bei voellig unpassendem Text`() = runTest {
        val result = engine.classify("Das Wetter heute ist ziemlich wechselhaft in Norddeutschland")
        assertNull(result.intentName)
    }

    @Test
    fun `extrahiert Uhrzeit und Inhalt als Slots bei Erinnerung`() = runTest {
        val result = engine.classify("Erinnere mich um 15 Uhr an die Tabletten")
        assertEquals("erinnerung_stellen", result.intentName)
        assertEquals("15 uhr", result.slots["uhrzeit"])
        assertEquals("die tabletten", result.slots["inhalt"])
    }

    @Test
    fun `abbrechen wird erkannt`() = runTest {
        val result = engine.classify("Stopp bitte")
        assertEquals("abbrechen", result.intentName)
    }
}
