package com.shotgun.smsbot.service

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class RcsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "RcsNotifListener"
        private const val MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MESSAGES_PACKAGE) return

        val extras = sbn.notification.extras
        val sender = extras.getString(Notification.EXTRA_TITLE) ?: run {
            Log.w(TAG, "Notification sans titre, ignorée")
            return
        }
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: run {
            Log.w(TAG, "Notification sans corps, ignorée")
            return
        }

        Log.i(TAG, "━━━ RCS REÇU ━━━")
        Log.i(TAG, "  Expéditeur : $sender")
        Log.i(TAG, "  Body : ${body.take(200)}")

        val serviceIntent = Intent(this, SmsProcessingService::class.java).apply {
            putExtra("sender", sender)
            putExtra("body", body)
        }
        startService(serviceIntent)
    }
}
