package com.shotgun.smsbot.util

object DateNormalizer {

    /**
     * Normalize any dd/mm variant to zero-padded "dd/mm".
     *  "3/6"  → "03/06"
     *  "03/6" → "03/06"
     *  "3/06" → "03/06"
     */
    fun normalize(date: String): String {
        val parts = date.split("/")
        if (parts.size != 2) return date
        return "${parts[0].trim().padStart(2, '0')}/${parts[1].trim().padStart(2, '0')}"
    }

    /** Normalize a list, silently dropping malformed entries. */
    fun normalizeList(dates: List<String>): List<String> =
        dates.map { normalize(it.trim()) }
             .filter { it.matches(Regex("""\d{2}/\d{2}""")) }
}
