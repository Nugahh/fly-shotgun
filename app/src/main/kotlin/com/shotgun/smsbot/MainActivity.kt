package com.shotgun.smsbot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shotgun.smsbot.config.AppConfig
import com.shotgun.smsbot.databinding.ActivityMainBinding
import com.shotgun.smsbot.util.DateNormalizer

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "Permissions manquantes : ${denied.joinToString()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppConfig.load(this)
        populateFields()
        setupSaveButton()
        permissionLauncher.launch(requiredPermissions)
        checkBatteryOptimization()
    }

    private fun populateFields() {
        binding.etSenderNumber.setText(AppConfig.senderNumber)
        binding.etCallNumber.setText(AppConfig.callNumber)
        binding.etKeyword.setText(AppConfig.keyword)
        binding.etAvailableDates.setText(AppConfig.availableDates.joinToString("\n"))
        binding.switchEnabled.isChecked = AppConfig.isEnabled
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            val rawDates = binding.etAvailableDates.text.toString()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val normalizedDates = DateNormalizer.normalizeList(rawDates)

            AppConfig.save(
                context = this,
                sender  = binding.etSenderNumber.text.toString(),
                callTo  = binding.etCallNumber.text.toString(),
                kw      = binding.etKeyword.text.toString(),
                dates   = normalizedDates,
                enabled = binding.switchEnabled.isChecked
            )

            // Refresh l'affichage avec les dates normalisées
            binding.etAvailableDates.setText(normalizedDates.joinToString("\n"))

            Toast.makeText(
                this,
                "Sauvegardé — ${normalizedDates.size} date(s) configurée(s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Optimisation batterie")
                .setMessage(
                    "Pour que le bot fonctionne quand l'écran est éteint, " +
                    "désactivez l'optimisation batterie pour cette app."
                )
                .setPositiveButton("Désactiver") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }
}
