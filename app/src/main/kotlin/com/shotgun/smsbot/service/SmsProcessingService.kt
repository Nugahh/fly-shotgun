package com.shotgun.smsbot.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.shotgun.smsbot.config.AppConfig
import com.shotgun.smsbot.util.SmsLlmInterpreter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SmsProcessingService : Service() {

    companion object {
        private const val TAG = "SmsProcessingService"
    }

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sender = intent?.getStringExtra("sender") ?: run { stopSelf(startId); return START_NOT_STICKY }
        val body   = intent.getStringExtra("body")    ?: run { stopSelf(startId); return START_NOT_STICKY }

        scope.launch {
            processSms(sender, body)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private suspend fun processSms(sender: String, body: String) {
        AppConfig.load(this)

        if (!AppConfig.isEnabled) {
            Log.d(TAG, "Bot désactivé, SMS ignoré")
            return
        }

        if (!phoneNumbersMatch(sender, AppConfig.senderNumber)) {
            Log.d(TAG, "Expéditeur '$sender' ne correspond pas à '${AppConfig.senderNumber}'")
            return
        }

        if (!SmsLlmInterpreter.isAvailable(this)) {
            Log.w(TAG, "Modèle LLM absent — SMS ignoré. Télécharge le modèle dans l'app.")
            return
        }

        val smsDate = SmsLlmInterpreter.extractDate(this, body)

        if (smsDate == null) {
            Log.d(TAG, "LLM : pas de disponibilité détectée")
            return
        }

        if (smsDate !in AppConfig.availableDates) {
            Log.d(TAG, "Date $smsDate pas dans la liste ${AppConfig.availableDates}")
            return
        }

        Log.i(TAG, "MATCH ! Date=$smsDate → appel vers ${AppConfig.callNumber}")

        val callIntent = Intent(this, CallForegroundService::class.java).apply {
            putExtra("call_number", AppConfig.callNumber)
            putExtra("matched_date", smsDate)
        }
        startForegroundService(callIntent)
    }

    private fun phoneNumbersMatch(received: String, configured: String): Boolean {
        if (configured.isBlank()) return false
        if (received.trim().equals(configured.trim(), ignoreCase = true)) return true
        val digitsReceived   = received.replace(Regex("[^\\d]"), "").takeLast(9)
        val digitsConfigured = configured.replace(Regex("[^\\d]"), "").takeLast(9)
        return digitsReceived.isNotEmpty() && digitsReceived == digitsConfigured
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
