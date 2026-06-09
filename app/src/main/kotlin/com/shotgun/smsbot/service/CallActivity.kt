package com.shotgun.smsbot.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log

class CallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra("call_number") ?: run { finish(); return }
        Log.i("CallActivity", "Lancement appel vers $number")
        try {
            startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
            })
        } catch (e: Exception) {
            Log.e("CallActivity", "Échec appel", e)
        }
        finish()
    }
}
