package com.shotgun.smsbot.util

object SmsParser {

    /**
     * Extrait le premier dd/mm valide dans [body] et le retourne normalisé ("03/06"),
     * ou null si aucune date trouvée.
     *
     * Le regex valide les jours 01-31 et les mois 01-12 (avec ou sans zéro en tête).
     */
    private val DATE_REGEX = Regex("""\b(0?[1-9]|[12]\d|3[01])/(0?[1-9]|1[0-2])\b""")

    fun extractDate(body: String): String? {
        val match = DATE_REGEX.find(body) ?: return null
        val day   = match.groupValues[1].padStart(2, '0')
        val month = match.groupValues[2].padStart(2, '0')
        return "$day/$month"
    }

    /**
     * Retourne true si le message passe le filtre :
     *  1. [keyword] est vide OU le body commence par [keyword] (insensible à la casse)
     *  2. Le body contient une date dd/mm parseable
     */
    fun matchesFilter(body: String, keyword: String): Boolean {
        if (keyword.isNotBlank() && !body.trimStart().startsWith(keyword, ignoreCase = true)) {
            return false
        }
        return extractDate(body) != null
    }
}
