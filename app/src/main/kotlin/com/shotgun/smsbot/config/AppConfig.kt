package com.shotgun.smsbot.config

import android.content.Context
import android.content.SharedPreferences
import com.shotgun.smsbot.util.DateNormalizer

/**
 * Source de vérité unique pour toute la configuration utilisateur.
 * Stockée dans un fichier SharedPreferences privé.
 *
 * Schéma :
 *   "sender_number"    String   — numéro/nom de l'expéditeur à surveiller
 *   "call_number"      String   — numéro à appeler si match
 *   "keyword"          String   — le SMS doit commencer par ce mot (vide = pas de filtre)
 *   "available_dates"  String   — JSON minimal de dates "dd/mm", ex: ["15/06","20/06"]
 *   "bot_enabled"      Boolean  — interrupteur principal on/off
 */
object AppConfig {

    private const val PREFS_NAME   = "shotgun_config"
    private const val KEY_SENDER   = "sender_number"
    private const val KEY_CALL     = "call_number"
    private const val KEY_KEYWORD  = "keyword"
    private const val KEY_DATES    = "available_dates"
    private const val KEY_ENABLED  = "bot_enabled"

    var senderNumber: String     = ""
        private set
    var callNumber: String       = ""
        private set
    var keyword: String          = ""
        private set
    var availableDates: List<String> = emptyList()
        private set
    var isEnabled: Boolean       = true
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        senderNumber   = p.getString(KEY_SENDER, "")  ?: ""
        callNumber     = p.getString(KEY_CALL, "")    ?: ""
        keyword        = p.getString(KEY_KEYWORD, "") ?: ""
        availableDates = parseDates(p.getString(KEY_DATES, "[]") ?: "[]")
        isEnabled      = p.getBoolean(KEY_ENABLED, true)
    }

    fun save(
        context: Context,
        sender: String,
        callTo: String,
        kw: String,
        dates: List<String>,
        enabled: Boolean = true
    ) {
        prefs(context).edit()
            .putString(KEY_SENDER,  sender.trim())
            .putString(KEY_CALL,    callTo.trim())
            .putString(KEY_KEYWORD, kw.trim())
            .putString(KEY_DATES,   encodeDates(DateNormalizer.normalizeList(dates)))
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        load(context)
    }

    // Encodage JSON minimal — évite de tirer une dépendance JSON pour un tableau de strings.
    private fun encodeDates(dates: List<String>): String =
        "[" + dates.joinToString(",") { "\"$it\"" } + "]"

    private fun parseDates(json: String): List<String> {
        if (json == "[]" || json.isBlank()) return emptyList()
        return json.removeSurrounding("[", "]")
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.matches(Regex("""\d{2}/\d{2}""")) }
    }
}
