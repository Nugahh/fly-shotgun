package com.shotgun.smsbot

import com.shotgun.smsbot.util.SmsLlmInterpreter
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests unitaires pour le parsing de la réponse brute du LLM.
 * Aucun modèle ni contexte Android requis.
 */
class SmsLlmInterpreterParseTest {

    private fun parse(raw: String) = SmsLlmInterpreter.parseResponse(raw)

    // ── Réponses valides ──────────────────────────────────────────────────────

    @Test fun `date deja normalisee`() = assertEquals("02/06", parse("02/06"))

    @Test fun `date sans zeros → padStart`() = assertEquals("09/06", parse("9/6"))

    @Test fun `jour sans zero seulement`() = assertEquals("05/12", parse("5/12"))

    @Test fun `mois sans zero seulement`() = assertEquals("20/07", parse("20/7"))

    @Test fun `date avec espaces autour`() = assertEquals("09/06", parse("  09/06  "))

    @Test fun `date noyee dans du texte`() = assertEquals("15/06", parse("La date est 15/6."))

    @Test fun `date avec annee — premier groupe seulement`() =
        assertEquals("02/06", parse("02/06/2026"))

    // ── NONE — toutes variantes de casse ─────────────────────────────────────

    @Test fun `NONE majuscules → null`() = assertNull(parse("NONE"))

    @Test fun `none minuscules → null`() = assertNull(parse("none"))

    @Test fun `None mixte → null`() = assertNull(parse("None"))

    @Test fun `phrase contenant NONE → null`() =
        assertNull(parse("I found NONE dates available."))

    // ── Réponses vides ou inutilisables ──────────────────────────────────────

    @Test fun `vide → null`() = assertNull(parse(""))

    @Test fun `texte sans date ni NONE → null`() =
        assertNull(parse("Désolé, je n'ai pas compris."))

    // ── Valeurs hors bornes ───────────────────────────────────────────────────

    @Test fun `jour 0 invalide → null`() = assertNull(parse("00/06"))

    @Test fun `jour 32 invalide → null`() = assertNull(parse("32/06"))

    @Test fun `mois 0 invalide → null`() = assertNull(parse("15/00"))

    @Test fun `mois 13 invalide → null`() = assertNull(parse("15/13"))
}
