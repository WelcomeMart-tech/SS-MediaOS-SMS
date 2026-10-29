package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

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

    private lateinit var verifyMobileButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var activateDeviceButton: Button

    private val statusPrefsName = "ss_mediaos_gateway_status"

    companion object {
        private const val PREF_SERVICE_RUNNING = "service_running"
        private const val PREF_SERVER_STATE = "server_state"
        private const val PREF_SERVER_CHECK_AT = "server_check_at"

        private const val STATE_CONNECTED = "CONNECTED"
        private const val STATE_FAILED = "FAILED"
        private const val STATE_CONNECTING = "CONNECTING"
    }

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

        verifyMobileButton = findViewById(R.id.verifyMobileButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)

        // Main Server URL is controlled by SecureConfig.
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.visibility = View.GONE

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this).orEmpty()
        )
        deviceIdInput.isEnabled = false

        loadSettings()

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testConnectionButton.setOnClickListener {
            verifyOrganizationAndMobile()
        }

        verifyMobileButton.setOnClickListener {
            verifyOrganizationAndMobile()
        }

        activateDeviceButton.setOnClickListener {
            activateDevice()
        }

        updateSimStatusPlaceholder()
    }

    private fun prefs() =
        getSharedPreferences(statusPrefsName, MODE_PRIVATE)

    private fun loadSettings() {
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)

        apiTokenInput.setText(
            SecureConfig.getCompanyApiKey(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this).orEmpty()
        )

        organizationCodeInput.setText(
            prefs().getString("organization_code", "").orEmpty()
        )

        registeredMobileInput.setText(
            prefs().getString("registered_mobile", "").orEmpty()
        )

        organizationNameText.text =
            prefs().getString("organization_name", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { "Institution Name: $it" }
                ?: "Institution Name: Not verified"

        organizationStatusText.text =
            prefs().getString("organization_status", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { "Verification: $it" }
                ?: "Verification: PENDING"

        updateActivationStatus()
    }

    /**
     * Save the existing settings without marking the institution verified.
     * Saving settings is not the same as server-side verification.
     */
    private fun saveSettings(): Boolean {
        val companyApiKey = apiTokenInput.text.toString().trim()
        val deviceCode = SecureConfig.getDeviceCode(this).orEmpty().trim()
        val institutionCode = organizationCodeInput.text.toString().trim()
        val mobile = registeredMobileInput.text.toString().trim()

        if (companyApiKey.isBlank()) {
            apiTokenInput.error = "Company API Key required"
            apiTokenInput.requestFocus()
            return false
        }

        if (institutionCode.isBlank()) {
            organizationCodeInput.error = "Institution Code required"
            organizationCodeInput.requestFocus()
            return false
        }

        if (mobile.isBlank()) {
            registeredMobileInput.error = "Registered mobile number required"
            registeredMobileInput.requestFocus()
            return false
        }

        if (mobile.any { !it.isDigit() } || mobile.length !in 8..15) {
            registeredMobileInput.error =
                "Enter a valid mobile number (8–15 digits)"
            registeredMobileInput.requestFocus()
            return false
        }

        if (deviceCode.isBlank()) {
            connectionStatusText.text =
                "DEVICE_CODE_MISSING: Installation Device ID is unavailable."
            return false
        }

        return try {
            val oldApiKey =
                SecureConfig.getCompanyApiKey(this).orEmpty()

            val oldCode =
                prefs().getString("organization_code", "").orEmpty()

            val oldMobile =
                prefs().getString("registered_mobile", "").orEmpty()

            val saved = SecureConfig.saveActivationSettings(
                context = this,
                serverUrl = SecureConfig.DEFAULT_SERVER_URL,
                deviceCode = deviceCode,
                activationToken = companyApiKey
            )

            if (!saved) {
                connectionStatusText.text =
                    "SETTINGS_SAVE_FAILED: Settings could not be saved."
                return false
            }

            val identityChanged =
                oldApiKey != companyApiKey ||
                oldCode != institutionCode ||
                oldMobile != mobile

            val editor = prefs().edit()
                .putString("organization_code", institutionCode)
                .putString("registered_mobile", mobile)

            if (identityChanged) {
                editor.putBoolean("organization_verified", false)
                    .putBoolean("diagnostics_passed", false)
                    .putString("organization_status", "PENDING")
                    .remove("organization_name")
                    .remove("diagnostic_report")

                SecureConfig.clearDeviceToken(this)

                organizationNameText.text =
                    "Institution Name: Not verified"

                organizationStatusText.text =
                    "Verification: PENDING"

                activationStatusText.text =
                    "Device status: NOT ACTIVATED"
            }

            editor.apply()

            if (identityChanged) {
                connectionStatusText.text =
                    "Settings saved. Run verification before activation."
            } else {
                connectionStatusText.text =
                    "Settings saved. Verification status has not been changed."
            }

            updateActivationStatus()

            Toast.makeText(
                this,
                "Settings saved",
                Toast.LENGTH_SHORT
            ).show()

            true
        } catch (_: Exception) {
            connectionStatusText.text =
                "SETTINGS_SAVE_FAILED: Could not save settings."
            false
        }
    }

    /**
     * Ask Main Server to verify the complete connection chain.
     *
     * The app does not directly connect to the Client Database.
     * It trusts verification only when Main Server returns all
     * required diagnostic checks with status PASS and ok=true.
     */
    private fun verifyOrganizationAndMobile() {
        if (!saveSettings()) return

        val apiKey = apiTokenInput.text.toString().trim()
        val institutionCode = organizationCodeInput.text.toString().trim()
        val mobile = registeredMobileInput.text.toString().trim()

        val requestedIdentity = "$apiKey|$institutionCode|$mobile"

        // Fail closed before starting a new diagnostic.
        prefs().edit()
            .putBoolean("organization_verified", false)
            .putBoolean("diagnostics_passed", false)
            .putString("organization_status", "CHECKING")
            .remove("organization_name")
            .remove("diagnostic_report")
            .apply()

        SecureConfig.clearDeviceToken(this)

        organizationNameText.text = "Institution Name: Checking..."
        organizationStatusText.text = "Verification: CHECKING"
        activationStatusText.text = "Device status: NOT ACTIVATED"

        connectionStatusText.text =
            "Checking Main Server, Client API and Client Database..."

        setButtonsEnabled(false)

        ApiClient.diagnoseGateway(
            companyApiKey = apiKey,
            institutionCode = institutionCode,
            registeredMobileNumber = mobile
        ) { result ->

            runOnUiThread {
                setButtonsEnabled(true)

                val currentIdentity =
                    "${apiTokenInput.text.toString().trim()}|" +
                    "${organizationCodeInput.text.toString().trim()}|" +
                    registeredMobileInput.text.toString().trim()

                if (currentIdentity != requestedIdentity) {
                    SecureConfig.clearDeviceToken(this)

                    prefs().edit()
                        .putBoolean("organization_verified", false)
                        .putBoolean("diagnostics_passed", false)
                        .putString("organization_status", "PENDING")
                        .remove("organization_name")
                        .remove("diagnostic_report")
                        .apply()

                    organizationNameText.text =
                        "Institution Name: Not verified"

                    organizationStatusText.text =
                        "Verification: PENDING"

                    connectionStatusText.text =
                        "IDENTITY_CHANGED: Details changed during verification. Verify again."

                    updateActivationStatus()
                    return@runOnUiThread
                }

                result.fold(
                    onSuccess = { response ->
                        processDiagnosticResponse(response)
                    },
                    onFailure = { error ->
                        val message = error.message
                            ?: "Could not contact Main Server."

                        prefs().edit()
                            .putBoolean("organization_verified", false)
                            .putBoolean("diagnostics_passed", false)
                            .putString("organization_status", "FAILED")
                            .putString(
                                "diagnostic_report",
                                "FAIL — $message"
                            )
                            .apply()

                        organizationNameText.text =
                            "Institution Name: Not verified"

                        organizationStatusText.text =
                            "Verification: FAILED"

                        connectionStatusText.text =
                            "DIAGNOSTIC REQUEST FAILED\n$message"

                        SecureConfig.clearDeviceToken(this)
                        updateActivationStatus()
                    }
                )
            }
        }
    }

    /**
     * Validate the diagnostic response from Main Server.
     *
     * A missing check, an unknown status, or a failed check must
     * never be treated as a successful verification.
     */
    private fun processDiagnosticResponse(response: JSONObject) {
        val checks = response.optJSONObject("checks")

        if (checks == null) {
            failDiagnostic(
                code = response.optString(
                    "code",
                    "DIAGNOSTIC_CHECKS_MISSING"
                ),
                message = response.optString(
                    "message",
                    "Main Server did not return the required diagnostic checks."
                ),
                report = "FAIL — Main Server did not return a checks object."
            )
            return
        }

        val requiredChecks = listOf(
            "main_server",
            "company_auth",
            "client_api_config",
            "client_api",
            "client_database",
            "institution"
        )

        val reportLines = mutableListOf<String>()
        var allPassed = response.optBoolean("ok", false)

        for (checkName in requiredChecks) {
            val check = checks.optJSONObject(checkName)

            if (check == null) {
                allPassed = false
                reportLines.add(
                    "FAIL  $checkName — CHECK_RESULT_MISSING"
                )
                continue
            }

            val status = check.optString("status", "UNKNOWN")
                .trim()
                .uppercase()

            val code = check.optString("code", "NO_ERROR_CODE")
                .trim()

            val message = check.optString(
                "message",
                "No diagnostic message returned."
            ).trim()

            val passed = status == "PASS"

            if (!passed) {
                allPassed = false
            }

            reportLines.add(
                "${if (passed) "PASS" else "FAIL"}  $checkName"
            )
            reportLines.add("  Code: $code")
            reportLines.add("  Message: $message")
        }

        val report = reportLines.joinToString("\n")
        prefs().edit()
            .putString("diagnostic_report", report)
            .apply()

        if (!allPassed) {
            val overallCode = response.optString(
                "code",
                "GATEWAY_DIAGNOSTICS_FAILED"
            )

            failDiagnostic(
                code = overallCode,
                message = response.optString(
                    "message",
                    "One or more required checks failed."
                ),
                report = report
            )
            return
        }

        /*
         * The Main Server may return the institution name.
         * Name matching is not used to identify the institution.
         * The server must verify its code and company relationship.
         */
        val institutionName = response.optString(
            "institutionName",
            response.optString("organizationName", "")
        ).trim()

        prefs().edit()
            .putBoolean("organization_verified", true)
            .putBoolean("diagnostics_passed", true)
            .putString("organization_status", "VERIFIED")
            .putString("organization_name", institutionName)
            .apply()

        organizationNameText.text =
            if (institutionName.isNotBlank()) {
                "Institution Name: $institutionName"
            } else {
                "Institution Code: Verified"
            }

        organizationStatusText.text =
            "Verification: ALL REQUIRED CHECKS PASSED"

        connectionStatusText.text =
            "GATEWAY DIAGNOSTICS: PASS\n\n$report\n\n" +
            "All required checks passed. You can activate the device."

        Toast.makeText(
            this,
            "Server verification successful",
            Toast.LENGTH_LONG
        ).show()

        updateActivationStatus()
    }

    private fun failDiagnostic(
        code: String,
        message: String,
        report: String
    ) {
        prefs().edit()
            .putBoolean("organization_verified", false)
            .putBoolean("diagnostics_passed", false)
            .putString("organization_status", "FAILED")
            .remove("organization_name")
            .putString(
                "diagnostic_report",
                "ERROR: $code\n$message\n\n$report"
            )
            .apply()

        SecureConfig.clearDeviceToken(this)

        organizationNameText.text =
            "Institution Name: Not verified"

        organizationStatusText.text =
            "Verification: FAILED ($code)"

        connectionStatusText.text =
            "GATEWAY DIAGNOSTICS: FAILED\n" +
            "Error: $code\n" +
            "Details: $message\n\n" +
            report

        activationStatusText.text =
            "Device status: NOT ACTIVATED"

        updateActivationStatus()
    }

    /**
     * Device activation is blocked unless the complete diagnostic
     * response has passed.
     */
    private fun activateDevice() {
        if (!saveSettings()) return

        val verified = prefs().getBoolean(
            "organization_verified",
            false
        )

        val diagnosticsPassed = prefs().getBoolean(
            "diagnostics_passed",
            false
        )

        if (!verified || !diagnosticsPassed) {
            connectionStatusText.text =
                "ACTIVATION_BLOCKED: Complete all server diagnostics successfully first."

            activationStatusText.text =
                "Device status: NOT ACTIVATED"

            return
        }

        if (SecureConfig.getDeviceCode(this).isNullOrBlank()) {
            connectionStatusText.text =
                "DEVICE_CODE_MISSING: Device ID is unavailable."
            return
        }

        if (SecureConfig.getCompanyApiKey(this).isNullOrBlank()) {
            connectionStatusText.text =
                "COMPANY_API_KEY_REQUIRED: Company API Key is missing."
            return
        }

        activateDeviceButton.isEnabled = false
        activateDeviceButton.text = "Connecting..."

        connectionStatusText.text =
            "Sending activation request to Main Server..."

        activationStatusText.text =
            "Device status: ACTIVATION IN PROGRESS"

        setServerState(STATE_CONNECTING)

        ApiClient.activate { result ->
            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Verify/Connect Device"

                result.fold(
                    onSuccess = { response ->
                        if (
                            response.optBoolean("ok", false) &&
                            !SecureConfig.getDeviceToken(this).isNullOrBlank()
                        ) {
                            connectionStatusText.text =
                                "Device activated by Main Server."

                            activationStatusText.text =
                                "Device status: ACTIVATED"

                            setServerState(STATE_CONNECTED)

                            Toast.makeText(
                                this,
                                "Gateway device activated",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            SecureConfig.clearDeviceToken(this)

                            connectionStatusText.text =
                                "ACTIVATION_RESPONSE_INVALID: Main Server did not confirm activation."

                            activationStatusText.text =
                                "Device status: NOT ACTIVATED"

                            setServerState(STATE_FAILED)
                        }
                    },
                    onFailure = { error ->
                        SecureConfig.clearDeviceToken(this)

                        val message = error.message
                            ?: "Activation failed."

                        connectionStatusText.text =
                            "DEVICE_ACTIVATION_FAILED\n$message"

                        activationStatusText.text =
                            "Device status: NOT ACTIVATED"

                        setServerState(STATE_FAILED)
                    }
                )

                updateActivationStatus()
            }
        }
    }

    private fun updateSimStatusPlaceholder() {
        simStatusText.text =
            "SIM status: Checked by SMS sending service"
    }

    private fun updateActivationStatus() {
        val deviceToken =
            SecureConfig.getDeviceToken(this)

        val apiKey =
            SecureConfig.getCompanyApiKey(this)

        val deviceCode =
            SecureConfig.getDeviceCode(this)

        val verified =
            prefs().getBoolean("organization_verified", false)

        val diagnosticsPassed =
            prefs().getBoolean("diagnostics_passed", false)

        if (
            deviceToken.isNullOrBlank() ||
            apiKey.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            !verified ||
            !diagnosticsPassed
        ) {
            activationStatusText.text =
                "Device status: NOT ACTIVATED"
            return
        }

        activationStatusText.text =
            "Device status: ACTIVATED"

        val serviceRunning = prefs().getBoolean(
            PREF_SERVICE_RUNNING,
            false
        )

        val serverState = prefs().getString(
            PREF_SERVER_STATE,
            STATE_CONNECTING
        ) ?: STATE_CONNECTING

        val lastCheckAt = prefs().getLong(
            PREF_SERVER_CHECK_AT,
            0L
        )

        val isRecent =
            lastCheckAt > 0L &&
            System.currentTimeMillis() - lastCheckAt <= 30000L

        connectionStatusText.text = when {
            !serviceRunning ->
                "Device activated. Gateway service is not running."

            !isRecent ->
                "Device activated. Waiting for a recent server check."

            serverState == STATE_CONNECTED ->
                "Device activated. Server connection confirmed."

            serverState == STATE_FAILED ->
                "Device activated, but the latest server connection failed."

            else ->
                "Device activated. Waiting for server connection..."
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        verifyMobileButton.isEnabled = enabled
        testConnectionButton.isEnabled = enabled
        saveSettingsButton.isEnabled = enabled
        activateDeviceButton.isEnabled = enabled
    }

    private fun setServerState(state: String) {
        prefs().edit()
            .putString(PREF_SERVER_STATE, state)
            .putLong(
                PREF_SERVER_CHECK_AT,
                System.currentTimeMillis()
            )
            .apply()
    }

    override fun onResume() {
        super.onResume()

        if (::activationStatusText.isInitialized) {
            updateActivationStatus()
        }
    }
}
