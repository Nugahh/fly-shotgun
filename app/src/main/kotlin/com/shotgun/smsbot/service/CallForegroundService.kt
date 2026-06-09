package com.shotgun.smsbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shotgun.smsbot.R
import com.shotgun.smsbot.config.AppConfig

class CallForegroundService : Service() {

    companion object {
        private const val TAG             = "CallForegroundService"
        private const val CHANNEL_ID      = "shotgun_call_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_ALARM       = "com.shotgun.smsbot.STOP_ALARM"
    }

    private var mediaPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            stopAlarm()
            Log.i(TAG, "Alarme arrêtée manuellement")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val callNumber  = intent?.getStringExtra("call_number")  ?: run { stopSelf(); return START_NOT_STICKY }
        val matchedDate = intent.getStringExtra("matched_date") ?: ""

        // Obligatoire dans les 5 secondes sur Android 12+ pour éviter une ANR.
        startForeground(NOTIFICATION_ID, buildNotification(callNumber, matchedDate))

        val alarmOnly = intent.getBooleanExtra("alarm_only", false)
        Log.i(TAG, if (alarmOnly) "Test alarme" else "Passage d'appel vers $callNumber (date: $matchedDate)")
        AppConfig.load(this)
        // Pour le test manuel, on joue toujours l'alarme indépendamment du switch
        val alarmPlayed = if (alarmOnly || AppConfig.alarmEnabled) playAlarm() else false
        if (!alarmOnly) placeCall(callNumber, forceSpeakerphone = alarmPlayed)

        if (alarmOnly) {
            // Garde le service vivant le temps de l'alarme (10s) pour que la notification reste visible
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }, 11_000L)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun playAlarm(): Boolean {
        return try {
            val audioManager = getSystemService(AudioManager::class.java)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = if (AppConfig.alarmUri.isNotEmpty())
                Uri.parse(AppConfig.alarmUri)
            else
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            stopAlarm()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setLegacyStreamType(AudioManager.STREAM_ALARM)
                        .build()
                )
                setDataSource(applicationContext, alarmUri)
                prepare()
                start()
            }
            Log.i(TAG, "✓ Alarme déclenchée via MediaPlayer (volume max)")

            Handler(Looper.getMainLooper()).postDelayed({ stopAlarm() }, 10_000L)
            true
        } catch (e: Exception) {
            Log.e(TAG, "✗ Impossible de jouer l'alarme", e)
            false
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun placeCall(number: String, forceSpeakerphone: Boolean = false) {
        try {
            val telecom = getSystemService(TelecomManager::class.java)
            val uri = Uri.fromParts("tel", number, null)
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, forceSpeakerphone || AppConfig.speakerphoneEnabled)
            }
            telecom.placeCall(uri, extras)
            Log.i(TAG, "✓ Appel placé via TelecomManager")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ Permission refusée — fallback ACTION_CALL", e)
            fallbackActionCall(number)
        } catch (e: Exception) {
            Log.e(TAG, "✗ TelecomManager.placeCall() échoué — fallback ACTION_CALL", e)
            fallbackActionCall(number)
        }
    }

    private fun fallbackActionCall(number: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "✗ Fallback ACTION_CALL aussi échoué", e)
        }
    }

    private fun buildNotification(number: String, date: String): Notification {
        val text = if (date.isNotEmpty()) "Vol le $date → appel vers $number"
                   else "Appel automatique vers $number"
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CallForegroundService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shotgun : appel en cours")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Arrêter alarme", stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Appels automatiques Shotgun",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description          = "Notification lors d'un appel automatique déclenché par SMS"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
