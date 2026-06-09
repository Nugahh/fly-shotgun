package com.shotgun.smsbot.util

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsLlmInterpreter {

    private const val TAG = "SmsLlmInterpreter"

    @Volatile private var inference: LlmInference? = null

    fun isAvailable(context: Context): Boolean = ModelDownloadManager.isModelReady(context)

    /**
     * Essaie d'extraire une date dd/mm du SMS via le SLM.
     * Retourne null si le modèle est indisponible ou si aucune date n'est trouvée.
     */
    suspend fun extractDate(context: Context, body: String): String? = withContext(Dispatchers.Default) {
        val llm = getOrCreate(context) ?: return@withContext null
        try {
            val response = llm.generateResponse(buildPrompt(body))
            parseResponse(response).also { result ->
                Log.d(TAG, "SLM réponse brute='$response' → résultat=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur inférence SLM", e)
            null
        }
    }

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
                    .setMaxTokens(128)   // prompt ~50 tokens + réponse max ~10 tokens
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
    private fun buildPrompt(body: String): String =
        "<start_of_turn>user\n" +
        "Tu analyses des SMS Transavia pour détecter une disponibilité de place de vol.\n" +
        "Réponds UNIQUEMENT avec la date au format dd/mm (ex: 15/06) si le SMS indique une vraie disponibilité de place.\n" +
        "Réponds NONE si c'est une annulation, une modification de planning, une information générale, ou s'il n'y a pas de disponibilité.\n" +
        "Pas d'explication, juste la date ou NONE.\n" +
        "SMS: $body<end_of_turn>\n" +
        "<start_of_turn>model\n"

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
