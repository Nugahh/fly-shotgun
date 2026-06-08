package com.shotgun.smsbot

import com.shotgun.smsbot.util.SmsParser
import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {

    // --- extractDate ---

    @Test fun `extrait date dd slash mm standard`() {
        assertEquals("15/06", SmsParser.extractDate("Vol disponible le 15/06 Paris-Nantes"))
    }

    @Test fun `normalise jour sans zero`() {
        assertEquals("03/06", SmsParser.extractDate("Mission le 3/6"))
    }

    @Test fun `normalise mois sans zero`() {
        assertEquals("20/07", SmsParser.extractDate("Dispo 20/7 pour toi"))
    }

    @Test fun `retourne null si pas de date`() {
        assertNull(SmsParser.extractDate("Pas de date dans ce message"))
    }

    @Test fun `ignore date invalide jour 32`() {
        // 32/06 ne doit pas matcher (regex valide 01-31)
        assertNull(SmsParser.extractDate("Vol le 32/06"))
    }

    @Test fun `ignore date invalide mois 13`() {
        assertNull(SmsParser.extractDate("Vol le 15/13"))
    }

    @Test fun `extrait premiere date sur plusieurs`() {
        // Prend la première date valide trouvée
        assertEquals("10/06", SmsParser.extractDate("Vol 10/06 ou 20/07"))
    }

    // --- matchesFilter ---

    @Test fun `passe filtre sans keyword et avec date`() {
        assertTrue(SmsParser.matchesFilter("SHOTGUN vol 15/06", ""))
    }

    @Test fun `echoue filtre si keyword present mais body ne commence pas par keyword`() {
        assertFalse(SmsParser.matchesFilter("Bonjour, vol 15/06", "SHOTGUN"))
    }

    @Test fun `passe filtre avec keyword correct`() {
        assertTrue(SmsParser.matchesFilter("SHOTGUN vol disponible 15/06", "SHOTGUN"))
    }

    @Test fun `filtre keyword insensible a la casse`() {
        assertTrue(SmsParser.matchesFilter("shotgun vol 15/06", "SHOTGUN"))
    }

    @Test fun `echoue filtre si pas de date meme avec keyword`() {
        assertFalse(SmsParser.matchesFilter("SHOTGUN aucune date ici", "SHOTGUN"))
    }
}
