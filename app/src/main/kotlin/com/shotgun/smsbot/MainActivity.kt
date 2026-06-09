package com.shotgun.smsbot

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.MaterialDatePicker
import com.shotgun.smsbot.config.AppConfig
import com.shotgun.smsbot.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedDates = mutableListOf<String>()
    private var selectedAlarmUri: String = ""
    private var activeFrom: String = "00:00"
    private var activeTo: String   = "23:59"

    private val ringtoneLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            selectedAlarmUri = uri?.toString() ?: ""
            updateAlarmSoundLabel()
            saveAlarmUri()
        }
    }

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
            Toast.makeText(this, "Permissions manquantes : ${denied.joinToString()}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppConfig.load(this)
        populateFields()
        setupSaveButton()
        setupAlarmSwitch()
        setupScheduleButtons()
        setupGeminiKeyEdit()
        setupPickAlarmSoundButton()
        setupTestLlmButton()
        setupTestAlarmButton()
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
        binding.switchAlarm.isChecked = AppConfig.alarmEnabled
        binding.switchEnabled.isChecked = AppConfig.isEnabled
        activeFrom = AppConfig.activeFrom
        activeTo   = AppConfig.activeTo
        binding.btnTimeFrom.text = activeFrom
        binding.btnTimeTo.text   = activeTo
        updateScheduleHint()
        selectedAlarmUri = AppConfig.alarmUri
        updateAlarmSoundLabel()
        selectedDates.clear()
        selectedDates.addAll(AppConfig.availableDates)
        refreshDateChips()
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            AppConfig.save(
                context      = this,
                sender       = binding.etSenderNumber.text.toString(),
                callTo       = binding.etCallNumber.text.toString(),
                kw           = binding.etKeyword.text.toString(),
                dates        = selectedDates.toList(),
                enabled      = binding.switchEnabled.isChecked,
                geminiKey      = binding.etGeminiKey.text.toString(),
                speakerphone   = binding.switchSpeakerphone.isChecked,
                alarm          = binding.switchAlarm.isChecked,
                alarmUriStr    = selectedAlarmUri,
                activeFromStr = activeFrom,
                activeToStr   = activeTo
            )
            lockGeminiKey()
            Toast.makeText(this, "Sauvegardé — ${selectedDates.size} date(s) configurée(s)", Toast.LENGTH_SHORT).show()
        }

        binding.btnAddDate.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Choisir une date")
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = millis
                val day   = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
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

    private fun setupScheduleButtons() {
        binding.btnTimeFrom.setOnClickListener {
            val (h, m) = activeFrom.split(":").map { it.toInt() }
            TimePickerDialog(this, { _, hour, minute ->
                activeFrom = "%02d:%02d".format(hour, minute)
                binding.btnTimeFrom.text = activeFrom
                updateScheduleHint()
            }, h, m, true).show()
        }
        binding.btnTimeTo.setOnClickListener {
            val (h, m) = activeTo.split(":").map { it.toInt() }
            TimePickerDialog(this, { _, hour, minute ->
                activeTo = "%02d:%02d".format(hour, minute)
                binding.btnTimeTo.text = activeTo
                updateScheduleHint()
            }, h, m, true).show()
        }
    }

    private fun updateScheduleHint() {
        val fromMins = activeFrom.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        val toMins   = activeTo.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        binding.tvScheduleHint.text = if (fromMins > toMins) "(passe minuit)" else ""
    }

    private fun setupAlarmSwitch() {
        binding.switchAlarm.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) binding.switchSpeakerphone.isChecked = true
        }
    }

    private var geminiKeyEditing = false

    private fun setupGeminiKeyEdit() {
        lockGeminiKey()
        binding.btnEditGeminiKey.setOnClickListener {
            if (!geminiKeyEditing) {
                // Passe en mode édition
                geminiKeyEditing = true
                binding.etGeminiKey.isFocusable = true
                binding.etGeminiKey.isFocusableInTouchMode = true
                binding.etGeminiKey.requestFocus()
                binding.btnEditGeminiKey.text = "Enregistrer"
                binding.btnCancelGeminiKey.visibility = android.view.View.VISIBLE
            } else {
                // Sauvegarde immédiate de la clé
                AppConfig.save(
                    context      = this,
                    sender       = binding.etSenderNumber.text.toString(),
                    callTo       = binding.etCallNumber.text.toString(),
                    kw           = binding.etKeyword.text.toString(),
                    dates        = selectedDates.toList(),
                    enabled      = binding.switchEnabled.isChecked,
                    geminiKey    = binding.etGeminiKey.text.toString(),
                    speakerphone = binding.switchSpeakerphone.isChecked,
                    alarm        = binding.switchAlarm.isChecked,
                    alarmUriStr  = selectedAlarmUri,
                    activeFromStr = activeFrom,
                    activeToStr   = activeTo
                )
                lockGeminiKey()
                Toast.makeText(this, "Clé API sauvegardée", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCancelGeminiKey.setOnClickListener {
            binding.etGeminiKey.setText(AppConfig.geminiApiKey)
            lockGeminiKey()
        }
    }

    private fun lockGeminiKey() {
        geminiKeyEditing = false
        binding.etGeminiKey.isFocusable = false
        binding.etGeminiKey.isFocusableInTouchMode = false
        binding.etGeminiKey.clearFocus()
        binding.btnEditGeminiKey.text = "Modifier la clé"
        binding.btnCancelGeminiKey.visibility = android.view.View.GONE
    }

    private fun setupPickAlarmSoundButton() {
        binding.btnPickAlarmSound.setOnClickListener {
            val currentUri = if (selectedAlarmUri.isNotEmpty()) Uri.parse(selectedAlarmUri)
                             else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choisir le son d'alarme")
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            }
            ringtoneLauncher.launch(intent)
        }
    }

    private fun updateAlarmSoundLabel() {
        if (selectedAlarmUri.isEmpty()) {
            binding.tvAlarmSoundName.text = "Son par défaut"
            return
        }
        val name = RingtoneManager.getRingtone(this, Uri.parse(selectedAlarmUri))
            ?.getTitle(this) ?: "Son personnalisé"
        binding.tvAlarmSoundName.text = name
    }

    private fun saveAlarmUri() {
        AppConfig.save(
            context      = this,
            sender       = binding.etSenderNumber.text.toString(),
            callTo       = binding.etCallNumber.text.toString(),
            kw           = binding.etKeyword.text.toString(),
            dates        = selectedDates.toList(),
            enabled      = binding.switchEnabled.isChecked,
            geminiKey    = binding.etGeminiKey.text.toString(),
            speakerphone = binding.switchSpeakerphone.isChecked,
            alarm        = binding.switchAlarm.isChecked,
            alarmUriStr  = selectedAlarmUri
        )
    }

    private fun setupTestLlmButton() {
        binding.btnTestLlm.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "Colle ici le SMS à analyser"
                minLines = 4
                maxLines = 8
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                setPadding(48, 24, 48, 24)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("SMS à tester")
                .setView(input)
                .setPositiveButton("Envoyer au LLM") { _, _ ->
                    val sms = input.text.toString().trim()
                    if (sms.isEmpty()) return@setPositiveButton
                    binding.btnTestLlm.isEnabled = false
                    binding.btnTestLlm.text = "Appel Gemini en cours…"
                    lifecycleScope.launch {
                        val result = com.shotgun.smsbot.util.SmsLlmInterpreter.extractDate(this@MainActivity, sms)
                        binding.btnTestLlm.isEnabled = true
                        binding.btnTestLlm.text = "Tester le LLM"
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Résultat LLM")
                            .setMessage("SMS :\n\"$sms\"\n\nRésultat : ${result ?: "NONE — pas de dispo détectée"}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun setupTestAlarmButton() {
        binding.btnTestAlarm.setOnClickListener {
            startForegroundService(
                Intent(this, com.shotgun.smsbot.service.CallForegroundService::class.java).apply {
                    putExtra("call_number", "test")
                    putExtra("matched_date", "test")
                    putExtra("alarm_only", true)
                }
            )
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
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("Plus tard", null)
                .show()
        }
    }
}
