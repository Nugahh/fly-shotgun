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
import android.os.VibrationEffect
import android.os.Vibrator
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
        private const val ALARM_DURATION_MS = 15_000L
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCallNumber: String? = null
    private var pendingCallRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALARM) {
            pendingCallRunnable?.let { handler.removeCallbacks(it) }
            pendingCallRunnable = null
            stopAlarm()
            val number = pendingCallNumber
            pendingCallNumber = null
            if (number != null) {
                Log.i(TAG, "Alarme arrêtée manuellement — appel immédiat vers $number")
                placeCall(number, forceSpeakerphone = true)
            } else {
                Log.i(TAG, "Alarme arrêtée manuellement")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val callNumber  = intent?.getStringExtra("call_number")  ?: run { stopSelf(); return START_NOT_STICKY }
        val matchedDate = intent.getStringExtra("matched_date") ?: ""
        val alarmOnly   = intent.getBooleanExtra("alarm_only", false)

        AppConfig.load(this)
        val willPlayAlarm = alarmOnly || AppConfig.alarmEnabled

        // Obligatoire dans les 5 secondes sur Android 12+ pour éviter une ANR.
        startForeground(NOTIFICATION_ID, buildNotification(callNumber, matchedDate, willPlayAlarm && !alarmOnly))

        Log.i(TAG, if (alarmOnly) "Test alarme" else "Passage d'appel vers $callNumber (date: $matchedDate)")

        if (!willPlayAlarm) {
            if (!alarmOnly) placeCall(callNumber, forceSpeakerphone = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Joue l'alarme + vibration pendant 15s avant l'appel, pour que l'utilisateur
        // soit alerté avant que l'audio de l'appel ne coupe le son de l'alarme (Samsung).
        playAlarm()

        if (alarmOnly) {
            handler.postDelayed({
                stopAlarm()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }, ALARM_DURATION_MS)
        } else {
            pendingCallNumber = callNumber
            pendingCallRunnable = Runnable {
                stopAlarm()
                pendingCallNumber = null
                pendingCallRunnable = null
                placeCall(callNumber, forceSpeakerphone = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            handler.postDelayed(pendingCallRunnable!!, ALARM_DURATION_MS)
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
                isLooping = true
                prepare()
                start()
            }
            Log.i(TAG, "✓ Alarme déclenchée via MediaPlayer (volume max)")

            vibrator = getSystemService(Vibrator::class.java)?.also { v ->
                val pattern = longArrayOf(0, 1000, 500)
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            }
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
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
        vibrator = null
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

    private fun buildNotification(number: String, date: String, willCallOnStop: Boolean): Notification {
        val text = if (date.isNotEmpty()) "Vol le $date → appel vers $number"
                   else "Appel automatique vers $number"
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CallForegroundService::class.java).apply { action = ACTION_STOP_ALARM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionLabel = if (willCallOnStop) "Arrêter l'alarme et appeler" else "Arrêter l'alarme"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shotgun : appel en cours")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, actionLabel, stopPi)
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
