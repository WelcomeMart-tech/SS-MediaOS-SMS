package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Gateway settings and organization verification screen.
 * Organization identity is accepted only from ApiClient's successful
 * Main Server -> Client Database verification response.
 */
class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var organizationCodeInput: EditText
    private lateinit var registeredMobileInput: EditText

    private lateinit var organizationNameText: TextView
    private lateinit var organizationStatusText: TextView
    private lateinit var simStatusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var activationStatusText: TextView
    private lateinit var simSelectionSpinner: Spinner

    private lateinit var verifyMobileButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var activateDeviceButton: Button

    private val statusPrefsName = "ss_mediaos_gateway_status"
    private val simItems = mutableListOf<SimChoice>()

    private data class SimChoice(
        val subscriptionId: Int,
        val displayName: String,
        val number: String
    ) {
        override fun toString(): String = buildString {
            append(displayName)
            if (number.isNotBlank()) append(" • $number")
            append(" (SIM ID: $subscriptionId)")
        }
    }

    companion object {
        private const val PREF_SERVICE_RUNNING = "service_running"
        private const val PREF_SERVER_STATE = "server_state"
        private const val PREF_SERVER_CHECK_AT = "server_check_at"
        private const val STATE_CONNECTED = "CONNECTED"
        private const val STATE_FAILED = "FAILED"
        private const val STATE_CONNECTING = "CONNECTING"
        private const val REQUEST_READ_PHONE_STATE = 7102
    }

    private fun prefs() = getSharedPreferences(statusPrefsName, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        organizationCodeInput = findViewById(R.id.organizationCodeInput)
        registeredMobileInput = findViewById(R.id.registeredMobileInput)
        organizationNameText = findViewById(R.id.organizationNameText)
        organizationStatusText = findViewById(R.id.organizationStatusText)
        simStatusText = findViewById(R.id.simStatusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        activationStatusText = findViewById(R.id.activationStatusText)
        simSelectionSpinner = findViewById(R.id.simSelectionSpinner)
        verifyMobileButton = findViewById(R.id.verifyMobileButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)

        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.visibility = View.GONE
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        deviceIdInput.isEnabled = false

        loadSettings()
        loadAvailableSims()

        saveSettingsButton.setOnClickListener { saveSettings() }
        verifyMobileButton.setOnClickListener { verifyOrganization() }
        testConnectionButton.setOnClickListener { verifyOrganization() }
        activateDeviceButton.setOnClickListener { activateDevice() }
        simSelectionSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                SecureConfig.clearSelectedSimSubscriptionId(this@GatewaySettingsActivity)
                simStatusText.text = "SIM status: No SIM selected"
            }

            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = simItems.getOrNull(position) ?: return
                if (SecureConfig.saveSelectedSimSubscriptionId(this@GatewaySettingsActivity, selected.subscriptionId)) {
                    simStatusText.text = "Assigned SIM: ${selected.displayName} • Ready for SMS"
                }
            }
        }
    }

    private fun loadSettings() {
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        organizationCodeInput.setText(SecureConfig.getOrganizationCode(this))
        registeredMobileInput.setText(SecureConfig.getRegisteredMobile(this))
        renderOrganizationDetails()
        updateActivationStatus()
    }

    private fun renderOrganizationDetails() {
        val verified = SecureConfig.isOrganizationVerified(this)
        val name = SecureConfig.getOrganizationName(this)
        val id = SecureConfig.getOrganizationId(this)
        val code = SecureConfig.getOrganizationCode(this)
        val mobile = SecureConfig.getRegisteredMobile(this)
        val status = SecureConfig.getOrganizationStatus(this)

        organizationNameText.text = if (verified) {
            "Organization Name: $name\n" +
                "Organization ID: $id\n" +
                "Organization Code: $code\n" +
                "Registered Mobile Number: $mobile"
        } else {
            "Organization Name: Not verified\n" +
                "Organization ID: —\nOrganization Code: ${code.ifBlank { "—" }}\n" +
                "Registered Mobile Number: —"
        }
        organizationStatusText.text = if (verified) {
            "Organization Status: $status • VERIFIED"
        } else {
            "Organization Status: ${status.ifBlank { "PENDING" }} • NOT VERIFIED"
        }
    }

    /** Saving credentials/input never marks an organization verified. */
    private fun saveSettings(): Boolean {
        val apiKey = apiTokenInput.text.toString().trim()
        val code = organizationCodeInput.text.toString().trim().uppercase()
        val mobile = registeredMobileInput.text.toString().trim()
        val deviceCode = SecureConfig.getDeviceCode(this)

        if (apiKey.isBlank()) {
            apiTokenInput.error = "Company API Key required"
            apiTokenInput.requestFocus()
            return false
        }
        if (code.isBlank()) {
            organizationCodeInput.error = "Organization Code required"
            organizationCodeInput.requestFocus()
            return false
        }
        if (mobile.isBlank() || mobile.any { !it.isDigit() } || mobile.length !in 8..15) {
            registeredMobileInput.error = "Enter the registered mobile number (8–15 digits)"
            registeredMobileInput.requestFocus()
            return false
        }

        val oldApiKey = SecureConfig.getCompanyApiKey(this).orEmpty()
        val oldCode = SecureConfig.getOrganizationCode(this)
        val oldMobile = SecureConfig.getRegisteredMobile(this)
        if (!SecureConfig.saveActivationSettings(this, SecureConfig.DEFAULT_SERVER_URL, deviceCode, apiKey)) {
            connectionStatusText.text = "SETTINGS_SAVE_FAILED: Could not save API credentials."
            return false
        }
        if (!SecureConfig.saveOrganizationIdentity(this, code, mobile)) {
            connectionStatusText.text = "ORGANIZATION_SETTINGS_FAILED: Could not save organization code/mobile."
            return false
        }
        if (oldApiKey != apiKey || oldCode != code || oldMobile != mobile) {
            SecureConfig.clearDeviceToken(this)
            prefs().edit().putBoolean("diagnostics_passed", false).apply()
            setServerState(STATE_CONNECTING)
        }
        renderOrganizationDetails()
        updateActivationStatus()
        connectionStatusText.text = "Settings saved. Verify the organization before activating the device."
        return true
    }

    private fun verifyOrganization() {
        if (!saveSettings()) return
        val apiKey = apiTokenInput.text.toString().trim()
        val code = organizationCodeInput.text.toString().trim().uppercase()
        val mobile = registeredMobileInput.text.toString().trim()
        val identityAtRequest = "$apiKey|$code|$mobile"

        SecureConfig.clearOrganizationVerification(this)
        prefs().edit().putBoolean("diagnostics_passed", false)
            .putString("organization_status", "CHECKING").apply()
        renderOrganizationDetails()
        activationStatusText.text = "Device status: NOT ACTIVATED"
        connectionStatusText.text = "Verifying Main Server, Client API and Client Database..."
        setButtonsEnabled(false)

        ApiClient.diagnoseGateway(apiKey, code, mobile) { result ->
            runOnUiThread {
                setButtonsEnabled(true)
                val currentIdentity = "${apiTokenInput.text.toString().trim()}|" +
                    "${organizationCodeInput.text.toString().trim().uppercase()}|" +
                    registeredMobileInput.text.toString().trim()
                if (currentIdentity != identityAtRequest) {
                    SecureConfig.clearOrganizationVerification(this)
                    prefs().edit().putBoolean("diagnostics_passed", false)
                        .putString("organization_status", "PENDING").apply()
                    connectionStatusText.text = "IDENTITY_CHANGED: Verify again using the current details."
                    renderOrganizationDetails()
                    updateActivationStatus()
                    return@runOnUiThread
                }
                result.fold(
                    onSuccess = { response ->
                        val checks = response.optJSONObject("checks")
                        val required = listOf("main_server", "company_auth", "client_api_config", "client_api", "client_database", "institution")
                        val allPass = response.optBoolean("ok", false) && checks != null &&
                            required.all { checks.optJSONObject(it)?.optString("status")?.equals("PASS", true) == true } &&
                            SecureConfig.isOrganizationVerified(this)
                        if (!allPass) {
                            SecureConfig.clearOrganizationVerification(this)
                            prefs().edit().putBoolean("diagnostics_passed", false)
                                .putString("organization_status", "FAILED").apply()
                            connectionStatusText.text = "VERIFICATION_FAILED: Required checks or verified organization data are missing."
                        } else {
                            prefs().edit().putBoolean("diagnostics_passed", true)
                                .putString("organization_status", "VERIFIED")
                                .putString("diagnostic_report", buildDiagnosticReport(checks!!)).apply()
                            connectionStatusText.text = "Organization verified successfully. Review the details below, then activate the device."
                            Toast.makeText(this, "Organization verified", Toast.LENGTH_LONG).show()
                        }
                        renderOrganizationDetails()
                        updateActivationStatus()
                    },
                    onFailure = { error ->
                        SecureConfig.clearOrganizationVerification(this)
                        prefs().edit().putBoolean("diagnostics_passed", false)
                            .putString("organization_status", "FAILED").apply()
                        renderOrganizationDetails()
                        connectionStatusText.text = "VERIFICATION_FAILED\n${error.message ?: "Main Server verification failed."}"
                        updateActivationStatus()
                    }
                )
            }
        }
    }

    private fun buildDiagnosticReport(checks: org.json.JSONObject): String {
        val names = listOf("main_server", "company_auth", "client_api_config", "client_api", "client_database", "institution")
        return names.joinToString("\n") { name ->
            val item = checks.optJSONObject(name)
            "$name: ${item?.optString("status", "MISSING") ?: "MISSING"} — ${item?.optString("message", "") ?: ""}"
        }
    }

    private fun activateDevice() {
        if (!saveSettings()) return
        if (!SecureConfig.isOrganizationVerified(this) || !prefs().getBoolean("diagnostics_passed", false)) {
            connectionStatusText.text = "ACTIVATION_BLOCKED: Verify an Active organization first."
            activationStatusText.text = "Device status: NOT ACTIVATED"
            return
        }
        val selectedId = SecureConfig.getSelectedSimSubscriptionId(this)
        if (selectedId == null || simItems.none { it.subscriptionId == selectedId }) {
            connectionStatusText.text = "SIM_REQUIRED: Select an available SIM before activation."
            simStatusText.text = "SIM status: Select an active SIM"
            return
        }
        activateDeviceButton.isEnabled = false
        activateDeviceButton.text = "Connecting..."
        connectionStatusText.text = "Sending activation request to Main Server..."
        ApiClient.activate { result ->
            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Verify/Connect Device"
                result.fold(
                    onSuccess = { response ->
                        if (response.optBoolean("ok", false) && !SecureConfig.getDeviceToken(this).isNullOrBlank()) {
                            connectionStatusText.text = "Device activated by Main Server."
                            activationStatusText.text = "Device status: ACTIVATED"
                            setServerState(STATE_CONNECTED)
                            Toast.makeText(this, "Gateway device activated", Toast.LENGTH_LONG).show()
                        } else {
                            SecureConfig.clearDeviceToken(this)
                            connectionStatusText.text = "ACTIVATION_RESPONSE_INVALID: Server did not issue a device token."
                            activationStatusText.text = "Device status: NOT ACTIVATED"
                            setServerState(STATE_FAILED)
                        }
                    },
                    onFailure = { error ->
                        SecureConfig.clearDeviceToken(this)
                        connectionStatusText.text = "DEVICE_ACTIVATION_FAILED\n${error.message ?: "Activation failed."}"
                        activationStatusText.text = "Device status: NOT ACTIVATED"
                        setServerState(STATE_FAILED)
                    }
                )
                updateActivationStatus()
            }
        }
    }

    private fun loadAvailableSims() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_READ_PHONE_STATE)
            simStatusText.text = "SIM status: Phone permission required to list SIM cards"
            simSelectionSpinner.isEnabled = false
            return
        }
        try {
            val manager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val active = manager.activeSubscriptionInfoList.orEmpty()
            simItems.clear()
            active.forEach { info: SubscriptionInfo ->
                val label = info.displayName?.toString()?.takeIf { it.isNotBlank() } ?: "SIM ${info.simSlotIndex + 1}"
                val number = info.number.orEmpty().filter { it.isDigit() }
                simItems.add(SimChoice(info.subscriptionId, label, number))
            }
            if (simItems.isEmpty()) {
                simSelectionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("No active SIM detected"))
                simSelectionSpinner.isEnabled = false
                simStatusText.text = "SIM status: No active SIM detected. Check SIM/network and permissions."
                SecureConfig.clearSelectedSimSubscriptionId(this)
                return
            }
            simSelectionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, simItems).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            simSelectionSpinner.isEnabled = true
            val savedId = SecureConfig.getSelectedSimSubscriptionId(this)
            val index = simItems.indexOfFirst { it.subscriptionId == savedId }
            if (index >= 0) {
                simSelectionSpinner.setSelection(index)
                simStatusText.text = "Assigned SIM: ${simItems[index].displayName} • Ready for SMS"
            } else {
                simSelectionSpinner.setSelection(0)
                SecureConfig.saveSelectedSimSubscriptionId(this, simItems[0].subscriptionId)
                simStatusText.text = "Assigned SIM: ${simItems[0].displayName} • Select the correct SIM if needed"
            }
        } catch (_: SecurityException) {
            simStatusText.text = "SIM status: Permission denied. Allow Phone permission and reopen settings."
            simSelectionSpinner.isEnabled = false
        } catch (e: Exception) {
            simStatusText.text = "SIM status: Could not read SIM information. ${e.javaClass.simpleName}"
            simSelectionSpinner.isEnabled = false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_PHONE_STATE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadAvailableSims()
            else simStatusText.text = "SIM status: Phone permission denied; SIM selection unavailable."
        }
    }

    private fun updateActivationStatus() {
        val activated = !SecureConfig.getDeviceToken(this).isNullOrBlank() &&
            !SecureConfig.getCompanyApiKey(this).isNullOrBlank() &&
            SecureConfig.isOrganizationVerified(this) &&
            prefs().getBoolean("diagnostics_passed", false)
        activationStatusText.text = if (activated) "Device status: ACTIVATED" else "Device status: NOT ACTIVATED"
        if (!activated) return
        val running = prefs().getBoolean(PREF_SERVICE_RUNNING, false)
        val state = prefs().getString(PREF_SERVER_STATE, STATE_CONNECTING) ?: STATE_CONNECTING
        val checkedAt = prefs().getLong(PREF_SERVER_CHECK_AT, 0L)
        val recent = checkedAt > 0L && System.currentTimeMillis() - checkedAt <= 30000L
        connectionStatusText.text = when {
            !running -> "Device activated. Gateway service is not running."
            !recent -> "Device activated. Waiting for server check."
            state == STATE_CONNECTED -> "Device activated. Server connection confirmed."
            state == STATE_FAILED -> "Device activated, but latest server connection failed."
            else -> "Device activated. Waiting for server connection..."
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        verifyMobileButton.isEnabled = enabled
        testConnectionButton.isEnabled = enabled
        saveSettingsButton.isEnabled = enabled
        activateDeviceButton.isEnabled = enabled
    }

    private fun setServerState(state: String) {
        prefs().edit().putString(PREF_SERVER_STATE, state)
            .putLong(PREF_SERVER_CHECK_AT, System.currentTimeMillis()).apply()
    }

    override fun onResume() {
        super.onResume()
        if (::activationStatusText.isInitialized) {
            loadAvailableSims()
            renderOrganizationDetails()
            updateActivationStatus()
        }
    }
}
