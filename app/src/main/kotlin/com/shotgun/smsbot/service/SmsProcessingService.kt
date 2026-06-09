package com.shotgun.smsbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shotgun.smsbot.R
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
        private const val CHANNEL_ID = "shotgun_processing_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sender = intent?.getStringExtra("sender") ?: run { stopSelf(startId); return START_NOT_STICKY }
        val body   = intent.getStringExtra("body")    ?: run { stopSelf(startId); return START_NOT_STICKY }

        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            processSms(sender, body)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shotgun : analyse en cours")
            .setContentText("Analyse du message par l'IA…")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Analyse SMS Shotgun",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private suspend fun processSms(sender: String, body: String) {
        Log.i(TAG, "━━━ TRAITEMENT SMS ━━━")
        AppConfig.load(this)
        Log.d(TAG, "Config : enabled=${AppConfig.isEnabled} | sender='${AppConfig.senderNumber}' | dates=${AppConfig.availableDates}")

        if (!AppConfig.isEnabled) {
            Log.w(TAG, "✗ Bot désactivé — SMS ignoré")
            return
        }
        Log.d(TAG, "✓ Bot activé")

        if (!AppConfig.isWithinActiveHours()) {
            Log.w(TAG, "✗ Hors créneau (${AppConfig.activeFrom}–${AppConfig.activeTo}) — SMS ignoré")
            return
        }
        Log.d(TAG, "✓ Dans le créneau horaire")

        if (!phoneNumbersMatch(sender, AppConfig.senderNumber)) {
            Log.w(TAG, "✗ Expéditeur ne correspond pas : reçu='$sender' | configuré='${AppConfig.senderNumber}'")
            return
        }
        Log.d(TAG, "✓ Expéditeur OK : '$sender'")

        if (!SmsLlmInterpreter.isAvailable(this)) {
            Log.w(TAG, "✗ Clé API Gemini manquante — configure-la dans l'app")
            return
        }
        Log.d(TAG, "✓ Clé Gemini configurée — lancement inférence…")

        val smsDate = SmsLlmInterpreter.extractDate(this, body)
        Log.i(TAG, "LLM résultat brut : '$smsDate'")

        if (smsDate == null) {
            Log.w(TAG, "✗ LLM : aucune disponibilité détectée dans ce SMS")
            return
        }
        Log.d(TAG, "✓ Date extraite : $smsDate")

        if (smsDate !in AppConfig.availableDates) {
            Log.w(TAG, "✗ Date '$smsDate' absente de la liste : ${AppConfig.availableDates}")
            return
        }
        Log.d(TAG, "✓ Date '$smsDate' présente dans la liste")

        Log.i(TAG, "MATCH — Date=$smsDate → appel vers ${AppConfig.callNumber}")

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
