package com.shotgun.smsbot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.shotgun.smsbot.service.SmsProcessingService

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action=${intent.action}")
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            Log.w(TAG, "Action ignorée : ${intent.action}")
            return
        }

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "Aucun message extrait de l'intent")
            return
        }

        val sender = messages[0].originatingAddress ?: run {
            Log.w(TAG, "Expéditeur null, SMS ignoré")
            return
        }
        val body = messages.joinToString("") { it.messageBody }

        Log.i(TAG, "━━━ SMS REÇU ━━━")
        Log.i(TAG, "  Expéditeur : $sender")
        Log.i(TAG, "  Body (${body.length} chars) : ${body.take(200)}")

        val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
            putExtra("sender", sender)
            putExtra("body", body)
        }
        context.startService(serviceIntent)
        Log.d(TAG, "SmsProcessingService démarré")
    }
}
