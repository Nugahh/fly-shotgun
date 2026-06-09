package com.shotgun.smsbot.util

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.shotgun.smsbot.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SmsLlmInterpreter {

    private const val TAG = "SmsLlmInterpreter"

    @Volatile private var inference: LlmInference? = null
    private val mutex = Mutex()

    fun isAvailable(context: Context): Boolean = ModelDownloadManager.isModelReady(context)

    /**
     * Essaie d'extraire une date dd/mm du SMS via le SLM.
     * Retourne null si le modèle est indisponible ou si aucune date n'est trouvée.
     */
    suspend fun extractDate(context: Context, body: String): String? = withContext(Dispatchers.Default) {
        val preprocessed = preprocessBody(body)
        Log.d(TAG, "Corps preprocessé : '$preprocessed'")
        // Gemini uniquement — pas de regex ni mots-clés
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
                    val delay = 5000L
                    Log.w(TAG, "⚠ Gemini 503 — retry dans 5s (tentative ${attempt + 1}/3)")
                    kotlinx.coroutines.delay(delay)
                } else {
                    Log.e(TAG, "✗ Erreur Gemini (tentative ${attempt + 1}/3)", e)
                }
            }
        }
        return null
    }

    private suspend fun llmAvailabilityCheck(context: Context, body: String, regexDate: String): String? {
        AppConfig.load(context)
        val apiKey = AppConfig.geminiApiKey
        return if (apiKey.isNotBlank()) {
            geminiAvailabilityCheck(apiKey, body, regexDate)
        } else {
            onDeviceLlmAvailabilityCheck(context, body, regexDate)
        }
    }

    private suspend fun geminiAvailabilityCheck(apiKey: String, body: String, regexDate: String): String? {
        Log.d(TAG, "━━━ GEMINI API ━━━")
        return try {
            val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
            val prompt = buildAvailabilityPrompt(body)
            val start    = System.currentTimeMillis()
            val response = model.generateContent(prompt).text?.trim()?.uppercase() ?: ""
            val elapsed  = System.currentTimeMillis() - start
            Log.i(TAG, "━━━ RÉPONSE GEMINI (${elapsed}ms) : '$response' ━━━")
            val hasOui = response.contains("OUI")
            val hasNon = response.contains("NON")
            when {
                hasNon && !hasOui -> { Log.w(TAG, "✗ Gemini : non-disponibilité"); null }
                else              -> { Log.i(TAG, "✓ Gemini : disponibilité → $regexDate"); regexDate }
            }
        } catch (e: Exception) {
            Log.e(TAG, "✗ Erreur Gemini API — fallback regex", e)
            regexDate
        }
    }

    private suspend fun onDeviceLlmAvailabilityCheck(context: Context, body: String, regexDate: String): String? =
        mutex.withLock {
            val llm = getOrCreate(context) ?: run {
                Log.w(TAG, "⚠ Modèle LLM absent — fallback regex : $regexDate")
                return@withLock regexDate
            }
            val prompt = buildAvailabilityPrompt(body)
            Log.d(TAG, "━━━ PROMPT LLM on-device ━━━\n$prompt")
            try {
                val start    = System.currentTimeMillis()
                val response = llm.generateResponse(prompt).trim().uppercase()
                val elapsed  = System.currentTimeMillis() - start
                Log.i(TAG, "━━━ RÉPONSE LLM (${elapsed}ms) : '$response' ━━━")
                val hasOui = response.contains("OUI")
                val hasNon = response.contains("NON")
                when {
                    hasNon && !hasOui -> { Log.w(TAG, "✗ LLM : non-disponibilité"); null }
                    else              -> { Log.i(TAG, "✓ LLM : disponibilité → $regexDate"); regexDate }
                }
            } catch (e: Exception) {
                Log.e(TAG, "✗ Erreur LLM — fallback regex", e)
                regexDate
            }
        }

    private fun isAvailabilitySms(body: String): Boolean {
        val lower = body.lowercase()
        return AVAILABILITY_KEYWORDS.any { lower.contains(it) }
    }

    private fun isCancellationSms(body: String): Boolean {
        val lower = body.lowercase()
        return CANCELLATION_KEYWORDS.any { lower.contains(it) }
    }

    private val AVAILABILITY_KEYWORDS = listOf(
        "recherchons", "recherche", "cherchons",
        "opl", "pnc", "copilote", "co-pilote",
        "regul pn", "régul pn", "regulation pn",
        "si dispo", "si vous etes dispo", "si disponible",
        "appel à", "appel a", "besoin de"
    )

    private val CANCELLATION_KEYWORDS = listOf(
        "annulé", "annule", "annulation",
        "modification du planning", "changement de planning",
        "modifié", "modifie", "reporté", "reporte",
        "supprimé", "supprime", "décalé", "decale",
        "ne partira pas", "vol supprimé"
    )

    /** Libère la ressource (appeler si l'app est détruite ou le modèle supprimé). */
    fun release() {
        synchronized(this) {
            inference?.close()
            inference = null
        }
    }

    // ── Privé ────────────────────────────────────────────────────────────────

    private fun getOrCreate(context: Context): LlmInference? {
        if (!ModelDownloadManager.isModelReady(context)) return null
        return inference ?: synchronized(this) {
            inference ?: try {
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(ModelDownloadManager.getModelPath(context))
                    .setMaxTokens(2048)  // prompt ~200-400 tokens + réponse ~10 tokens
                    .setTopK(1)          // greedy decoding → déterministe
                    .setTemperature(0.1f)
                    .build()
                LlmInference.createFromOptions(context.applicationContext, options)
                    .also { inference = it }
            } catch (e: Exception) {
                Log.e(TAG, "Impossible de charger le modèle LLM", e)
                null
            }
        }
    }

    /**
     * Format Gemma IT : <start_of_turn>user … <end_of_turn> <start_of_turn>model
     * Le modèle complète après le dernier tag.
     */
    private fun buildAvailabilityPrompt(body: String): String =
        "<start_of_turn>user\n" +
        "Voici un SMS reçu de Transavia :\n" +
        "---\n" +
        "$body\n" +
        "---\n" +
        "Ce SMS recherche-t-il du personnel navigant (OPL, PNC, copilote) pour un vol ? Réponds OUI ou NON.<end_of_turn>\n" +
        "<start_of_turn>model\n"

    private fun preprocessBody(body: String): String {
        val cal = java.util.Calendar.getInstance()
        val today    = dateString(cal)
        val tomorrow = dateString(cal, offset = 1)
        val dayAfter = dateString(cal, offset = 2)

        // Jours de la semaine → date concrète (prochain occurrence >= aujourd'hui)
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
            // Relatifs immédiats
            .replace(Regex("\\baprès[- ]?demain\\b",  RegexOption.IGNORE_CASE), dayAfter)
            .replace(Regex("\\bapres[- ]?demain\\b",  RegexOption.IGNORE_CASE), dayAfter)
            .replace(Regex("\\blendemain\\b",          RegexOption.IGNORE_CASE), tomorrow)
            .replace(Regex("\\bdemain\\b",             RegexOption.IGNORE_CASE), tomorrow)
            .replace(Regex("\\baujourd'hui\\b",        RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\baujourd'hui\\b",        RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bauj\\.?\\b",            RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bce soir\\b",            RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bcet? après[- ]?midi\\b",RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bcet? apres[- ]?midi\\b",RegexOption.IGNORE_CASE), today)
            .replace(Regex("\\bce matin\\b",           RegexOption.IGNORE_CASE), today)

        // Jours de la semaine nommés — seulement si PAS suivi d'une date dd/mm déjà présente
        for ((name, targetDow) in weekdays) {
            val pattern = Regex(
                "\\b(?:ce |prochain |pour le |pour |le )?$name(?:\\s+prochain)?\\b(?!\\s*\\d{1,2}/\\d{1,2})",
                RegexOption.IGNORE_CASE
            )
            val dateCal = java.util.Calendar.getInstance()
            var diff = (targetDow - dateCal.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
            if (diff == 0) diff = 7  // même jour = semaine prochaine
            dateCal.add(java.util.Calendar.DAY_OF_MONTH, diff)
            val dateStr = dateString(dateCal)
            result = result.replace(pattern, dateStr)
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
