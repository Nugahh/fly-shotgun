package com.shotgun.smsbot.config

import android.content.Context
import android.content.SharedPreferences
import com.shotgun.smsbot.util.DateNormalizer

object AppConfig {

    const val DEFAULT_CALL_NUMBER  = "+33185163211"
    const val DEFAULT_SENDER       = "0617186069"

    private const val PREFS_NAME    = "shotgun_config"
    private const val KEY_SENDER    = "sender_number"
    private const val KEY_CALL      = "call_number"
    private const val KEY_KEYWORD   = "keyword"
    private const val KEY_DATES     = "available_dates"
    private const val KEY_ENABLED   = "bot_enabled"
    private const val KEY_GEMINI_KEY    = "gemini_api_key"
    private const val KEY_SPEAKERPHONE  = "speakerphone_enabled"
    private const val KEY_ALARM_ENABLED = "alarm_enabled"
    private const val KEY_ALARM_URI     = "alarm_uri"
    private const val KEY_ACTIVE_FROM   = "active_from"
    private const val KEY_ACTIVE_TO     = "active_to"

    var senderNumber: String = ""
        private set
    var callNumber: String = ""
        private set
    var keyword: String = ""
        private set
    var availableDates: List<String> = emptyList()
        private set
    var isEnabled: Boolean = true
        private set
    var geminiApiKey: String = ""
        private set
    var speakerphoneEnabled: Boolean = false
        private set
    var alarmEnabled: Boolean = false
        private set
    var alarmUri: String = ""
        private set
    var activeFrom: String = "00:00"
        private set
    var activeTo: String = "23:59"
        private set

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        senderNumber        = p.getString(KEY_SENDER, DEFAULT_SENDER) ?: DEFAULT_SENDER
        callNumber          = p.getString(KEY_CALL, DEFAULT_CALL_NUMBER) ?: DEFAULT_CALL_NUMBER
        keyword             = p.getString(KEY_KEYWORD, "") ?: ""
        availableDates      = parseDates(p.getString(KEY_DATES, "[]") ?: "[]")
        isEnabled           = p.getBoolean(KEY_ENABLED, true)
        geminiApiKey        = p.getString(KEY_GEMINI_KEY, "") ?: ""
        speakerphoneEnabled = p.getBoolean(KEY_SPEAKERPHONE, false)
        alarmEnabled        = p.getBoolean(KEY_ALARM_ENABLED, false)
        alarmUri            = p.getString(KEY_ALARM_URI, "") ?: ""
        activeFrom          = p.getString(KEY_ACTIVE_FROM, "00:00") ?: "00:00"
        activeTo            = p.getString(KEY_ACTIVE_TO, "23:59") ?: "23:59"
    }

    fun save(
        context: Context,
        sender: String,
        callTo: String,
        kw: String,
        dates: List<String>,
        enabled: Boolean     = isEnabled,
        geminiKey: String    = geminiApiKey,
        speakerphone: Boolean = speakerphoneEnabled,
        alarm: Boolean       = alarmEnabled,
        alarmUriStr: String  = alarmUri,
        activeFromStr: String = activeFrom,
        activeToStr: String  = activeTo
    ) {
        prefs(context).edit()
            .putString(KEY_SENDER,   sender.trim())
            .putString(KEY_CALL,     callTo.trim())
            .putString(KEY_KEYWORD,  kw.trim())
            .putString(KEY_DATES,    encodeDates(DateNormalizer.normalizeList(dates)))
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_GEMINI_KEY,     geminiKey.trim())
            .putBoolean(KEY_SPEAKERPHONE,  speakerphone)
            .putBoolean(KEY_ALARM_ENABLED, alarm)
            .putString(KEY_ALARM_URI,      alarmUriStr)
            .putString(KEY_ACTIVE_FROM,    activeFromStr)
            .putString(KEY_ACTIVE_TO,      activeToStr)
            .apply()
        load(context)
    }

    fun isWithinActiveHours(): Boolean {
        val now     = java.util.Calendar.getInstance()
        val nowMins = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val fromMins = parseTimeMins(activeFrom)
        val toMins   = parseTimeMins(activeTo)
        return if (fromMins <= toMins) {
            nowMins in fromMins..toMins
        } else {
            nowMins >= fromMins || nowMins <= toMins
        }
    }

    private fun parseTimeMins(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0
        return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
    }

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
