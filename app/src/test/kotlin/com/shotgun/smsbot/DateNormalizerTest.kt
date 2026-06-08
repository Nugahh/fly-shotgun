package com.shotgun.smsbot

import com.shotgun.smsbot.util.DateNormalizer
import org.junit.Assert.*
import org.junit.Test

class DateNormalizerTest {

    @Test fun `normalise jour et mois sans zero`() {
        assertEquals("03/06", DateNormalizer.normalize("3/6"))
    }

    @Test fun `ne modifie pas une date deja normalisee`() {
        assertEquals("15/06", DateNormalizer.normalize("15/06"))
    }

    @Test fun `normalise uniquement le jour`() {
        assertEquals("03/12", DateNormalizer.normalize("3/12"))
    }

    @Test fun `normalise uniquement le mois`() {
        assertEquals("20/07", DateNormalizer.normalize("20/7"))
    }

    @Test fun `retourne input si format invalide`() {
        assertEquals("invalid", DateNormalizer.normalize("invalid"))
    }

    @Test fun `normalizeList filtre les entrees malformees`() {
        val input  = listOf("15/06", "3/6", "invalid", "20/07", "")
        val result = DateNormalizer.normalizeList(input)
        assertEquals(listOf("15/06", "03/06", "20/07"), result)
    }

    @Test fun `normalizeList trim les espaces`() {
        val result = DateNormalizer.normalizeList(listOf("  15/06  ", " 3/6 "))
        assertEquals(listOf("15/06", "03/06"), result)
    }
}
