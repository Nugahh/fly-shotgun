package com.shotgun.smsbot.util

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.shotgun.smsbot.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsLlmInterpreter {

    private const val TAG = "SmsLlmInterpreter"

    fun isAvailable(context: Context): Boolean {
        AppConfig.load(context)
        return AppConfig.geminiApiKey.isNotBlank()
    }

    suspend fun extractDate(context: Context, body: String): String? = withContext(Dispatchers.Default) {
        val preprocessed = preprocessBody(body)
        Log.d(TAG, "Corps preprocessé : '$preprocessed'")
        AppConfig.load(context)
        val apiKey = AppConfig.geminiApiKey
        if (apiKey.isBlank()) {
            Log.w(TAG, "✗ Pas de clé Gemini configurée")
            return@withContext null
        }
        geminiExtractDate(apiKey, preprocessed)
    }

    private suspend fun geminiExtractDate(apiKey: String, body: String): String? {
        val model  = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
        val prompt = "Analyse ce SMS Transavia.\n" +
            "Si c'est un recrutement de personnel navigant (OPL, PNC) pour un vol disponible, " +
            "réponds UNIQUEMENT avec la date au format dd/mm.\n" +
            "Sinon (annulation, changement de planning, info générale), réponds NONE.\n" +
            "Aucune explication.\n" +
            "SMS: $body"

        repeat(3) { attempt ->
            Log.d(TAG, "━━━ GEMINI API (tentative ${attempt + 1}/3) ━━━")
            try {
                val start    = System.currentTimeMillis()
                val response = model.generateContent(prompt).text?.trim() ?: ""
                val elapsed  = System.currentTimeMillis() - start
                Log.i(TAG, "━━━ RÉPONSE GEMINI (${elapsed}ms) : '$response' ━━━")
                return parseResponse(response)
            } catch (e: Exception) {
                val is503 = e.message?.contains("503") == true ||
                            e.message?.contains("high demand") == true ||
                            e.message?.contains("UNAVAILABLE") == true
                if (is503 && attempt < 2) {
                    Log.w(TAG, "⚠ Gemini 503 — retry dans 5s (tentative ${attempt + 1}/3)")
                    kotlinx.coroutines.delay(5000L)
                } else {
                    Log.e(TAG, "✗ Erreur Gemini (tentative ${attempt + 1}/3)", e)
                }
            }
        }
        return null
    }

    private fun preprocessBody(body: String): String {
        val cal = java.util.Calendar.getInstance()
        val today    = dateString(cal)
        val tomorrow = dateString(cal, offset = 1)
        val dayAfter = dateString(cal, offset = 2)

        val weekdays = mapOf(
            "lundi"    to 2, "monday"    to 2,
            "mardi"    to 3, "tuesday"   to 3,
            "mercredi" to 4, "wednesday" to 4,
            "jeudi"    to 5, "thursday"  to 5,
            "vendredi" to 6, "friday"    to 6,
            "samedi"   to 7, "saturday"  to 7,
            "dimanche" to 1, "sunday"    to 1
        )

        var result = body
            .replace(Regex("\\baprès[- ]?demain\\b",   RegexOption.IGNORE_CASE), dayAfter)
            .replace(Regex("\\bapres[- ]?demain\\b",   RegexOption.IGNORE_CASE), dayAfter)
            .replace(Regex("\\blendemain\\b",           RegexOption.IGNORE_CASE), tomorrow)
            .replace(Regex("\\bdemain\\b",              RegexOption.IGNORE_CASE), tomorrow)
            .replace(Regex("\\baujourd'hui\\b",         RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\baujourd'hui\\b",         RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bauj\\.?\\b",             RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bce soir\\b",             RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bcet? après[- ]?midi\\b", RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bcet? apres[- ]?midi\\b", RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bce matin\\b",            RegexOption.IGNORE_CASE), today)

        for ((name, targetDow) in weekdays) {
            val pattern = Regex(
                "\\b(?:ce |prochain |pour le |pour |le )?$name(?:\\s+prochain)?\\b(?!\\s*\\d{1,2}/\\d{1,2})",
                RegexOption.IGNORE_CASE
            )
            val dateCal = java.util.Calendar.getInstance()
            var diff = (targetDow - dateCal.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0) diff = 7
            dateCal.add(java.util.Calendar.DAY_OF_MONTH, diff)
            result = result.replace(pattern, dateString(dateCal))
        }

        return result
    }

    private fun dateString(cal: java.util.Calendar, offset: Int = 0): String {
        val c = cal.clone() as java.util.Calendar
        if (offset != 0) c.add(java.util.Calendar.DAY_OF_MONTH, offset)
        val d = c.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val m = (c.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        return "$d/$m"
    }

    private val RESPONSE_DATE_REGEX = Regex("""\b(\d{1,2})/(\d{1,2})\b""")

    internal fun parseResponse(raw: String): String? {
        val text = raw.trim()
        if (text.uppercase().contains("NONE")) return null
        val match = RESPONSE_DATE_REGEX.find(text) ?: return null
        val day   = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        if (day !in 1..31 || month !in 1..12) return null
        return "${day.toString().padStart(2, '0')}/${month.toString().padStart(2, '0')}"
    }
}
