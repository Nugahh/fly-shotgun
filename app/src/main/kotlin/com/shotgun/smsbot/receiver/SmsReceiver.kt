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
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Les SMS longs arrivent découpés en plusieurs SmsMessage — on reconstitue le body complet.
        val sender = messages[0].originatingAddress ?: return
        val body   = messages.joinToString("") { it.messageBody }

        Log.d(TAG, "SMS reçu de $sender (${body.length} chars)")

        // On délègue immédiatement — le BroadcastReceiver doit se terminer en < 10s.
        val serviceIntent = Intent(context, SmsProcessingService::class.java).apply {
            putExtra("sender", sender)
            putExtra("body", body)
        }
        context.startService(serviceIntent)
    }
}
