package com.shotgun.smsbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shotgun.smsbot.R

class CallForegroundService : Service() {

    companion object {
        private const val TAG             = "CallForegroundService"
        private const val CHANNEL_ID      = "shotgun_call_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callNumber  = intent?.getStringExtra("call_number")  ?: run { stopSelf(); return START_NOT_STICKY }
        val matchedDate = intent.getStringExtra("matched_date") ?: ""

        // Obligatoire dans les 5 secondes sur Android 12+ pour éviter une ANR.
        startForeground(NOTIFICATION_ID, buildNotification(callNumber, matchedDate))

        Log.i(TAG, "Passage d'appel vers $callNumber (date correspondante: $matchedDate)")
        placeCall(callNumber)

        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun placeCall(number: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data  = Uri.parse("tel:${Uri.encode(number)}")
                // FLAG_ACTIVITY_NEW_TASK obligatoire depuis un contexte Service
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission CALL_PHONE refusée", e)
        } catch (e: Exception) {
            Log.e(TAG, "Échec de l'appel", e)
        }
    }

    private fun buildNotification(number: String, date: String): Notification {
        val text = if (date.isNotEmpty()) "Vol le $date → appel vers $number"
                   else "Appel automatique vers $number"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Shotgun : appel en cours")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
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
