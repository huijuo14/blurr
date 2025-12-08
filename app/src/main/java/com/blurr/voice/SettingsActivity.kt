package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.blurr.voice.api.PicovoiceKeyManager
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.WakeWordManager
import kotlinx.coroutines.Job
import java.io.File

class SettingsActivity : BaseNavigationActivity() {

    private lateinit var apiSelectionGroup: RadioGroup
    private lateinit var proxySettingsLayout: LinearLayout
    private lateinit var proxyUrlEditText: EditText
    private lateinit var proxyApiKeyEditText: EditText
    private lateinit var proxyModelNameEditText: EditText
    private lateinit var resetToDefaultButton: Button
    private lateinit var switchShowThoughts: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var permissionsInfoButton: TextView
    private lateinit var batteryOptimizationHelpButton: TextView
    private lateinit var appVersionText: TextView
    private lateinit var editUserName: android.widget.EditText
    private lateinit var editUserEmail: android.widget.EditText
    private lateinit var editWakeWordKey: android.widget.EditText
    private lateinit var textGetPicovoiceKeyLink: TextView
    private lateinit var wakeWordButton: TextView
    private lateinit var buttonSignOut: Button
    private lateinit var wakeWordManager: WakeWordManager
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>


    private lateinit var sc: SpeechCoordinator
    private lateinit var sharedPreferences: SharedPreferences
    private var voiceTestJob: Job? = null

    companion object {
        private const val PREFS_NAME = "BlurrSettings"
        const val KEY_SHOW_THOUGHTS = "show_thoughts"
        const val KEY_API_SELECTION = "api_selection"
        const val KEY_PROXY_URL = "proxy_url"
        const val KEY_PROXY_API_KEY = "proxy_api_key"
        const val KEY_PROXY_MODEL_NAME = "proxy_model_name"
        private const val API_SELECTION_DEFAULT = "default"
        private const val API_SELECTION_PROXY = "proxy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize permission launcher first
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show()
                // The manager will handle the service start after permission is granted.
                wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
                updateWakeWordButtonState()
            } else {
                Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

        initialize()
        setupUI()
        loadAllSettings()
        setupAutoSavingListeners()
    }

    override fun onStop() {
        super.onStop()
        sc.stop()
        voiceTestJob?.cancel()
    }

    private fun initialize() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        wakeWordManager = WakeWordManager(this, requestPermissionLauncher)
    }

    private fun setupUI() {
        apiSelectionGroup = findViewById(R.id.apiSelectionGroup)
        proxySettingsLayout = findViewById(R.id.proxySettingsLayout)
        proxyUrlEditText = findViewById(R.id.proxyUrlEditText)
        proxyApiKeyEditText = findViewById(R.id.proxyApiKeyEditText)
        proxyModelNameEditText = findViewById(R.id.proxyModelNameEditText)
        resetToDefaultButton = findViewById(R.id.resetToDefaultButton)
        switchShowThoughts = findViewById(R.id.switchShowThoughts)
        permissionsInfoButton = findViewById(R.id.permissionsInfoButton)
        appVersionText = findViewById(R.id.appVersionText)
        batteryOptimizationHelpButton = findViewById(R.id.batteryOptimizationHelpButton)
      
        editWakeWordKey = findViewById(R.id.editWakeWordKey)
        wakeWordButton = findViewById(R.id.wakeWordButton)
        buttonSignOut = findViewById(R.id.buttonSignOut)

        editUserName = findViewById(R.id.editUserName)
        editUserEmail = findViewById(R.id.editUserEmail)
        textGetPicovoiceKeyLink = findViewById(R.id.textGetPicovoiceKeyLink)


        setupClickListeners()

        // Prefill profile fields from saved values
        kotlin.runCatching {
            val pm = UserProfileManager(this)
            editUserName.setText(pm.getName() ?: "")
            editUserEmail.setText(pm.getEmail() ?: "")
        }

        // Show app version
        val versionName = BuildConfig.VERSION_NAME
        appVersionText.text = "Version $versionName"
    }

    private fun setupClickListeners() {
        permissionsInfoButton.setOnClickListener {
            val intent = Intent(this, PermissionsActivity::class.java)
            startActivity(intent)
        }
        batteryOptimizationHelpButton.setOnClickListener {
            showBatteryOptimizationDialog()
        }
        wakeWordButton.setOnClickListener {
            val keyManager = PicovoiceKeyManager(this)
            
            val userKey = editWakeWordKey.text.toString().trim()
            if (userKey.isNotEmpty()) {
                keyManager.saveUserProvidedKey(userKey)
                Toast.makeText(this, "Wake word key saved.", Toast.LENGTH_SHORT).show()
            }
            
            val hasKey = !keyManager.getUserProvidedKey().isNullOrBlank()
            
            if (!hasKey) {
                showPicovoiceKeyRequiredDialog()
                return@setOnClickListener
            }
            
            wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateWakeWordButtonState() }, 500)
        }
        textGetPicovoiceKeyLink.setOnClickListener {
            val url = "https://console.picovoice.ai/login"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_SHORT).show()
                Log.e("SettingsActivity", "Failed to open Picovoice link", e)
            }
        }

        buttonSignOut.setOnClickListener {
            showSignOutConfirmationDialog()
        }

        resetToDefaultButton.setOnClickListener {
            proxyUrlEditText.setText(BuildConfig.GCLOUD_PROXY_URL)
            proxyApiKeyEditText.setText(BuildConfig.GCLOUD_PROXY_URL_KEY)
            proxyModelNameEditText.setText("llama-3.3-70b-versatile")
        }
    }

    private fun setupAutoSavingListeners() {
        apiSelectionGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.defaultApiRadio -> {
                    proxySettingsLayout.visibility = View.GONE
                    sharedPreferences.edit { putString(KEY_API_SELECTION, API_SELECTION_DEFAULT) }
                }
                R.id.proxyApiRadio -> {
                    proxySettingsLayout.visibility = View.VISIBLE
                    sharedPreferences.edit { putString(KEY_API_SELECTION, API_SELECTION_PROXY) }
                }
            }
        }

        proxyUrlEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                sharedPreferences.edit { putString(KEY_PROXY_URL, s.toString()) }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        proxyApiKeyEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                sharedPreferences.edit { putString(KEY_PROXY_API_KEY, s.toString()) }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        proxyModelNameEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                sharedPreferences.edit { putString(KEY_PROXY_MODEL_NAME, s.toString()) }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        switchShowThoughts.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(KEY_SHOW_THOUGHTS, isChecked).apply()
        }
    }

    private fun loadAllSettings() {
        val keyManager = PicovoiceKeyManager(this)
        editWakeWordKey.setText(keyManager.getUserProvidedKey() ?: "")
        updateWakeWordButtonState()
        switchShowThoughts.isChecked = sharedPreferences.getBoolean(KEY_SHOW_THOUGHTS, false)

        val apiSelection = sharedPreferences.getString(KEY_API_SELECTION, API_SELECTION_DEFAULT)
        if (apiSelection == API_SELECTION_PROXY) {
            apiSelectionGroup.check(R.id.proxyApiRadio)
            proxySettingsLayout.visibility = View.VISIBLE
        } else {
            apiSelectionGroup.check(R.id.defaultApiRadio)
            proxySettingsLayout.visibility = View.GONE
        }

        proxyUrlEditText.setText(sharedPreferences.getString(KEY_PROXY_URL, ""))
        proxyApiKeyEditText.setText(sharedPreferences.getString(KEY_PROXY_API_KEY, ""))
        proxyModelNameEditText.setText(sharedPreferences.getString(KEY_PROXY_MODEL_NAME, ""))
    }

    private fun showPicovoiceKeyRequiredDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Picovoice Key Required")
            .setMessage("To enable wake word functionality, you need a Picovoice AccessKey. You can get a free key from the Picovoice Console. Note: The Picovoice dashboard might not be available on mobile browsers sometimes - you may need to use a desktop browser.")
            .setPositiveButton("Get Key") { _, _ ->
                val url = "https://console.picovoice.ai/login"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link. No browser found or link unavailable on mobile. Please use a desktop browser.", Toast.LENGTH_LONG).show()
                    Log.e("SettingsActivity", "Failed to open Picovoice link", e)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
    }

    private fun updateWakeWordButtonState() {
        wakeWordManager.updateButtonState(wakeWordButton)
    }

    private fun showBatteryOptimizationDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_optimization_title))
            .setMessage(getString(R.string.battery_optimization_message))
            .setPositiveButton(getString(R.string.learn_how)) { _, _ ->
                val url = "https://tasker.joaoapps.com/userguide/en/faqs/faq-problem.html#00"
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = Uri.parse(url)
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_LONG).show()
                    Log.e("SettingsActivity", "Failed to open battery optimization link", e)
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
        
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(
            androidx.core.content.ContextCompat.getColor(this, R.color.white)
        )
    }

    private fun showSignOutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Sign Out")
            .setMessage("Are you sure you want to sign out? This will clear all your settings and data.")
            .setPositiveButton("Sign Out") { _, _ ->
                signOut()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun signOut() {
        val userProfileManager = UserProfileManager(this)
        userProfileManager.clearProfile()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    
    override fun getContentLayoutId(): Int = R.layout.activity_settings
    
    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SETTINGS
}