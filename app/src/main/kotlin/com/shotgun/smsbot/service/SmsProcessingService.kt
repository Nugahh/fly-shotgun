package com.shotgun.smsbot.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.shotgun.smsbot.config.AppConfig
import com.shotgun.smsbot.util.SmsParser
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
            process(sender, body)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun process(sender: String, body: String) {
        AppConfig.load(this)

        if (!AppConfig.isEnabled) {
            Log.d(TAG, "Bot désactivé, SMS ignoré")
            return
        }

        if (!phoneNumbersMatch(sender, AppConfig.senderNumber)) {
            Log.d(TAG, "Expéditeur '$sender' ne correspond pas à '${AppConfig.senderNumber}'")
            return
        }

        if (!SmsParser.matchesFilter(body, AppConfig.keyword)) {
            Log.d(TAG, "Le corps du SMS ne passe pas le filtre")
            return
        }

        val smsDate = SmsParser.extractDate(body) ?: return

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

    /**
     * Comparaison souple des numéros :
     *  1. Exacte insensible à la casse (codes courts alphanumériques : "TRANSAVIA")
     *  2. 9 derniers chiffres normalisés (+336XXXXXXXX vs 06XXXXXXXX)
     */
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
