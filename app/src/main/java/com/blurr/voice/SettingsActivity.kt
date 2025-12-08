package com.blurr.voice

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.NumberPicker
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
import com.blurr.voice.api.GoogleTts
import com.blurr.voice.api.PicovoiceKeyManager
import com.blurr.voice.api.TTSVoice
import com.blurr.voice.utilities.SpeechCoordinator
import com.blurr.voice.utilities.VoicePreferenceManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.WakeWordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SettingsActivity : BaseNavigationActivity() {

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
    private lateinit var apiSelectionGroup: RadioGroup
    private lateinit var customApiSettingsLayout: android.widget.LinearLayout
    private lateinit var editBaseUrl: android.widget.EditText
    private lateinit var editModelName: android.widget.EditText
    private lateinit var editApiKey: android.widget.EditText
    private lateinit var buttonSaveApiSettings: Button


    private lateinit var sc: SpeechCoordinator
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        private const val PREFS_NAME = "BlurrSettings"
        const val KEY_API_SELECTION = "api_selection"
        const val KEY_CUSTOM_API_BASE_URL = "custom_api_base_url"
        const val KEY_CUSTOM_API_MODEL_NAME = "custom_api_model_name"
        const val KEY_CUSTOM_API_KEY = "custom_api_key"
        const val KEY_SHOW_THOUGHTS = "show_thoughts"
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
        // Stop any lingering voice tests when the user leaves the screen
        sc.stop()
    }

    private fun initialize() {
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sc = SpeechCoordinator.getInstance(this)
        // Initialize wake word manager
        wakeWordManager = WakeWordManager(this, requestPermissionLauncher)
    }

    private fun setupUI() {
        permissionsInfoButton = findViewById(R.id.permissionsInfoButton)
        appVersionText = findViewById(R.id.appVersionText)
        batteryOptimizationHelpButton = findViewById(R.id.batteryOptimizationHelpButton)
        apiSelectionGroup = findViewById(R.id.apiSelectionGroup)
        customApiSettingsLayout = findViewById(R.id.customApiSettingsLayout)
        editBaseUrl = findViewById(R.id.editBaseUrl)
        editModelName = findViewById(R.id.editModelName)
        editApiKey = findViewById(R.id.editApiKey)
        buttonSaveApiSettings = findViewById(R.id.buttonSaveApiSettings)
      
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
            
            // Step 1: Save key if provided in the EditText
            val userKey = editWakeWordKey.text.toString().trim()
            if (userKey.isNotEmpty()) {
                keyManager.saveUserProvidedKey(userKey)
                Toast.makeText(this, "Wake word key saved.", Toast.LENGTH_SHORT).show()
            }
            
            // Step 2: Check if we have a key (either just saved or previously saved)
            val hasKey = !keyManager.getUserProvidedKey().isNullOrBlank()
            
            if (!hasKey) {
                showPicovoiceKeyRequiredDialog()
                return@setOnClickListener
            }
            
            // Step 3: Enable the wake word
            wakeWordManager.handleWakeWordButtonClick(wakeWordButton)
            // Give the service a moment to update its state before refreshing the UI
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateWakeWordButtonState() }, 500)
        }
        textGetPicovoiceKeyLink.setOnClickListener {
            val url = "https://console.picovoice.ai/login"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                // This might happen if the device has no web browser
                Toast.makeText(this, "Could not open link. No browser found.", Toast.LENGTH_SHORT).show()
                Log.e("SettingsActivity", "Failed to open Picovoice link", e)
            }
        }

        buttonSignOut.setOnClickListener {
            showSignOutConfirmationDialog()
        }
    }

    private fun setupAutoSavingListeners() {
        apiSelectionGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.defaultApiRadio -> {
                    customApiSettingsLayout.visibility = android.view.View.GONE
                }
                R.id.customApiRadio -> {
                    customApiSettingsLayout.visibility = android.view.View.VISIBLE
                }
            }
        }

        buttonSaveApiSettings.setOnClickListener {
            saveApiSettings()
        }
    }

    private fun loadAllSettings() {
        // Load API Settings
        val apiSelection = sharedPreferences.getString(KEY_API_SELECTION, "default")
        if (apiSelection == "custom") {
            apiSelectionGroup.check(R.id.customApiRadio)
            customApiSettingsLayout.visibility = android.view.View.VISIBLE
        } else {
            apiSelectionGroup.check(R.id.defaultApiRadio)
            customApiSettingsLayout.visibility = android.view.View.GONE
        }
        editBaseUrl.setText(sharedPreferences.getString(KEY_CUSTOM_API_BASE_URL, ""))
        editModelName.setText(sharedPreferences.getString(KEY_CUSTOM_API_MODEL_NAME, ""))
        editApiKey.setText(sharedPreferences.getString(KEY_CUSTOM_API_KEY, ""))

        // Inside loadAllSettings()
        val keyManager = PicovoiceKeyManager(this)
        editWakeWordKey.setText(keyManager.getUserProvidedKey() ?: "") // You will create this method next
        
        // Update wake word button state
        updateWakeWordButtonState()
    }

    private fun saveApiSettings() {
        val selectedApi = if (apiSelectionGroup.checkedRadioButtonId == R.id.customApiRadio) "custom" else "default"
        sharedPreferences.edit {
            putString(KEY_API_SELECTION, selectedApi)
            if (selectedApi == "custom") {
                putString(KEY_CUSTOM_API_BASE_URL, editBaseUrl.text.toString())
                putString(KEY_CUSTOM_API_MODEL_NAME, editModelName.text.toString())
                putString(KEY_CUSTOM_API_KEY, editApiKey.text.toString())
            }
        }
        Toast.makeText(this, "API settings saved.", Toast.LENGTH_SHORT).show()
    }

    private fun showPicovoiceKeyRequiredDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Picovoice Key Required")
            .setMessage("To enable wake word functionality, you need a Picovoice AccessKey. You can get a free key from the Picovoice Console. Note: The Picovoice dashboard might not be available on mobile browsers sometimes - you may need to use a desktop browser.")
            .setPositiveButton("Get Key") { _, _ ->
                // Try to open Picovoice console
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
        
        // Set button text colors to white
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
                // Open the Tasker FAQ URL
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
        
        // Set button text colors to white
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
        // Clear User Profile
        val userProfileManager = UserProfileManager(this)
        userProfileManager.clearProfile()

        // Clear all shared preferences for this app
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()


        // Restart the app by navigating to the onboarding screen
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    
    override fun getContentLayoutId(): Int = R.layout.activity_settings
    
    override fun getCurrentNavItem(): BaseNavigationActivity.NavItem = BaseNavigationActivity.NavItem.SETTINGS
}