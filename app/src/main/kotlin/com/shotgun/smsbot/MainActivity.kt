package com.shotgun.smsbot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.shotgun.smsbot.config.AppConfig
import com.shotgun.smsbot.databinding.ActivityMainBinding
import com.shotgun.smsbot.util.ModelDownloadManager
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedDates = mutableListOf<String>()

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
        setupModelSection()
        setupTestLlmButton()
        permissionLauncher.launch(requiredPermissions)
        checkBatteryOptimization()
        checkNotificationAccess()
        checkFullScreenIntentPermission()
    }

    private fun populateFields() {
        binding.etSenderNumber.setText(AppConfig.senderNumber)
        binding.etCallNumber.setText(AppConfig.callNumber)
        binding.etKeyword.setText(AppConfig.keyword)
        binding.etGeminiKey.setText(AppConfig.geminiApiKey)
        binding.switchSpeakerphone.isChecked = AppConfig.speakerphoneEnabled
        binding.switchEnabled.isChecked = AppConfig.isEnabled
        selectedDates.clear()
        selectedDates.addAll(AppConfig.availableDates)
        refreshDateChips()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            AppConfig.save(
                context    = this,
                sender     = binding.etSenderNumber.text.toString(),
                callTo     = binding.etCallNumber.text.toString(),
                kw         = binding.etKeyword.text.toString(),
                dates      = selectedDates.toList(),
                enabled    = binding.switchEnabled.isChecked,
                geminiKey    = binding.etGeminiKey.text.toString(),
                speakerphone = binding.switchSpeakerphone.isChecked
            )
            Toast.makeText(
                this,
                "Sauvegardé — ${selectedDates.size} date(s) configurée(s)",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnAddDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Choisir une date")
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = millis
                val day   = (cal.get(Calendar.DAY_OF_MONTH)).toString().padStart(2, '0')
                val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
                val date  = "$day/$month"
                if (date !in selectedDates) {
                    selectedDates.add(date)
                    selectedDates.sort()
                    refreshDateChips()
                }
            }
            picker.show(supportFragmentManager, "date_picker")
        }
    }

    private fun refreshDateChips() {
        binding.chipGroupDates.removeAllViews()
        selectedDates.forEach { date ->
            val chip = Chip(this).apply {
                text = date
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    selectedDates.remove(date)
                    refreshDateChips()
                }
            }
            binding.chipGroupDates.addView(chip)
        }
    }

    private fun setupModelSection() {
        refreshModelStatus()

        if (!ModelDownloadManager.isModelReady(this)) {
            showDownloadConsentDialog()
        }

        binding.btnDownloadModel.setOnClickListener {
            if (!ModelDownloadManager.isOnWifi(this)) {
                Toast.makeText(this, R.string.model_download_wifi_warning, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startModelDownload()
        }
    }

    private fun showDownloadConsentDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.model_dialog_title)
            .setMessage(R.string.model_dialog_message)
            .setPositiveButton(R.string.model_dialog_confirm) { _, _ ->
                if (!ModelDownloadManager.isOnWifi(this)) {
                    Toast.makeText(this, R.string.model_download_wifi_warning, Toast.LENGTH_LONG).show()
                } else {
                    startModelDownload()
                }
            }
            .setNegativeButton(R.string.model_dialog_later, null)
            .show()
    }

    private fun refreshModelStatus() {
        val ready = ModelDownloadManager.isModelReady(this)
        binding.tvModelStatus.setText(
            if (ready) R.string.model_status_ready else R.string.model_status_absent
        )
        binding.btnDownloadModel.visibility = if (ready) View.GONE else View.VISIBLE
    }

    private fun startModelDownload() {
        binding.btnDownloadModel.isEnabled = false
        binding.tvModelStatus.setText(R.string.model_status_downloading)
        binding.progressModelDownload.visibility = View.VISIBLE
        binding.tvDownloadProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ModelDownloadManager.downloadIfNeeded(this@MainActivity) { progress ->
                    runOnUiThread {
                        binding.progressModelDownload.progress = progress
                        binding.tvDownloadProgress.text = "$progress %"
                    }
                }
                refreshModelStatus()
                binding.progressModelDownload.visibility = View.GONE
                binding.tvDownloadProgress.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Modèle téléchargé avec succès.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.progressModelDownload.visibility = View.GONE
                binding.tvDownloadProgress.visibility = View.GONE
                binding.btnDownloadModel.isEnabled = true
                refreshModelStatus()
                Toast.makeText(this@MainActivity, R.string.model_download_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupTestLlmButton() {
        binding.btnTestLlm.setOnClickListener {
            val testSms = "Bonsoir, nous recherchons des OPL pour demain le 10/06 sur ORY OZZ ORY. Si disponible merci de nous contacter. Regul PN"
            lifecycleScope.launch {
                binding.btnTestLlm.isEnabled = false
                binding.btnTestLlm.text = "LLM en cours…"
                val result = com.shotgun.smsbot.util.SmsLlmInterpreter.extractDate(this@MainActivity, testSms)
                runOnUiThread {
                    binding.btnTestLlm.isEnabled = true
                    binding.btnTestLlm.text = "Tester le LLM"
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Résultat LLM")
                        .setMessage("SMS test :\n\"$testSms\"\n\nRésultat : ${result ?: "NONE (pas de dispo détectée)"}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun checkNotificationAccess() {
        val granted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        if (!granted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notif_access_title)
                .setMessage(R.string.notif_access_message)
                .setPositiveButton(R.string.notif_access_confirm) { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton(R.string.model_dialog_later, null)
                .show()
        }
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (!nm.canUseFullScreenIntent()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission requise")
                    .setMessage("Pour déclencher un appel quand l'écran est verrouillé, autorisez les notifications plein écran.")
                    .setPositiveButton("Autoriser") { _, _ ->
                        startActivity(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENTS").apply {
                            data = android.net.Uri.parse("package:$packageName")
                        })
                    }
                    .setNegativeButton("Plus tard", null)
                    .show()
            }
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
