package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

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
        private const val CONNECTION_TIMEOUT = 15000

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

        organizationCodeInput =
            findViewById(R.id.organizationCodeInput)

        registeredMobileInput =
            findViewById(R.id.registeredMobileInput)

        organizationNameText =
            findViewById(R.id.organizationNameText)

        organizationStatusText =
            findViewById(R.id.organizationStatusText)

        simStatusText =
            findViewById(R.id.simStatusText)

        verifyMobileButton =
            findViewById(R.id.verifyMobileButton)

        connectionStatusText =
            findViewById(R.id.connectionStatusText)

        activationStatusText =
            findViewById(R.id.activationStatusText)

        saveSettingsButton =
            findViewById(R.id.saveSettingsButton)

        testConnectionButton =
            findViewById(R.id.testConnectionButton)

        activateDeviceButton =
            findViewById(R.id.activateDeviceButton)

        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.visibility = View.GONE

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this)
        )
        deviceIdInput.isEnabled = false

        loadSettings()

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testConnectionButton.setOnClickListener {
            testConnection()
        }

        verifyMobileButton.setOnClickListener {
            verifyOrganization()
        }

        activateDeviceButton.setOnClickListener {
            activateDevice()
        }

        updateSimStatus()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        )

        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)

        apiTokenInput.setText(
            SecureConfig.getCompanyApiKey(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this)
        )

        organizationCodeInput.setText(
            SecureConfig.getOrganizationCode(this)
        )

        registeredMobileInput.setText(
            SecureConfig.getRegisteredMobile(this)
        )

        // The SecureConfig value is authoritative.
        val verified = SecureConfig.isOrganizationVerified(this)

        if (verified) {
            organizationNameText.text =
                "Organization Name: ${SecureConfig.getOrganizationName(this)}"

            organizationStatusText.text =
                "Organization Verification: VERIFIED"
        } else {
            organizationNameText.text =
                "Organization Name: Not verified"

            organizationStatusText.text =
                "Organization Verification: PENDING"
        }

        prefs.edit()
            .putBoolean("organization_verified", verified)
            .apply()

        updateActivationStatus()
    }

    /**
     * Save credentials and entered identity.
     * Saving does not itself verify the organization.
     */
    private fun saveSettings(): Boolean {
        val activationToken =
            apiTokenInput.text.toString().trim()

        val organizationCode =
            organizationCodeInput.text.toString().trim()

        val mobile =
            registeredMobileInput.text.toString().trim()

        val deviceCode = SecureConfig.getDeviceCode(this)

        if (activationToken.isBlank()) {
            apiTokenInput.error = "Company API Token required"
            apiTokenInput.requestFocus()
            return false
        }

        if (organizationCode.isBlank()) {
            organizationCodeInput.error = "Organization Code required"
            organizationCodeInput.requestFocus()
            return false
        }

        if (!mobile.matches(Regex("^[0-9]{8,15}$"))) {
            registeredMobileInput.error =
                "Enter a valid registered mobile number"
            registeredMobileInput.requestFocus()
            return false
        }

        val oldToken =
            SecureConfig.getCompanyApiKey(this).orEmpty()

        val oldCode =
            SecureConfig.getOrganizationCode(this)

        val oldMobile =
            SecureConfig.getRegisteredMobile(this)

        val identityChanged =
            oldCode != organizationCode ||
            oldMobile != mobile ||
            oldToken != activationToken

        val credentialsSaved = SecureConfig.saveActivationSettings(
            context = this,
            serverUrl = SecureConfig.DEFAULT_SERVER_URL,
            deviceCode = deviceCode,
            activationToken = activationToken
        )

        if (!credentialsSaved) {
            connectionStatusText.text =
                "Could not save the activation token securely."
            return false
        }

        /*
         * Save the identity as pending if it changed.
         * SecureConfig clears previous verification and device token
         * when the organization code or mobile changes.
         */
        if (oldCode != organizationCode || oldMobile != mobile) {
            val identitySaved = SecureConfig.saveOrganizationIdentity(
                this,
                organizationCode,
                mobile
            )

            if (!identitySaved) {
                connectionStatusText.text =
                    "Could not save the organization details."
                return false
            }
        }

        if (identityChanged) {
            SecureConfig.clearDeviceToken(this)

            getSharedPreferences(statusPrefsName, MODE_PRIVATE)
                .edit()
                .putBoolean("organization_verified", false)
                .putString("organization_status", "PENDING")
                .remove("organization_name")
                .apply()

            organizationNameText.text =
                "Organization Name: Not verified"

            organizationStatusText.text =
                "Organization Verification: PENDING"

            activationStatusText.text =
                "Device status: NOT ACTIVATED"
        }

        updateActivationStatus()

        Toast.makeText(
            this,
            "Settings saved securely.",
            Toast.LENGTH_SHORT
        ).show()

        return true
    }

    /**
     * Verify organization through the server API.
     * Local values alone are never treated as verified.
     */
    private fun verifyOrganization() {
        if (!saveSettings()) return

        val organizationCode =
            organizationCodeInput.text.toString().trim()

        val mobile =
            registeredMobileInput.text.toString().trim()

        verifyMobileButton.isEnabled = false

        organizationNameText.text =
            "Organization Name: Checking..."

        organizationStatusText.text =
            "Organization Verification: CHECKING"

        connectionStatusText.text =
            "Checking organization details with the server..."

        activationStatusText.text =
            "Device status: NOT ACTIVATED"

        getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            .edit()
            .putBoolean("organization_verified", false)
            .putString("organization_status", "CHECKING")
            .remove("organization_name")
            .apply()

        SecureConfig.clearOrganizationVerification(this)

        /*
         * Capture input values so an old response cannot verify
         * details that the user has changed while the request runs.
         */
        val requestedCode = organizationCode
        val requestedMobile = mobile
        val requestedToken =
            apiTokenInput.text.toString().trim()

        ApiClient.verifyOrganization(
            organizationCode = requestedCode,
            registeredMobile = requestedMobile
        ) { result ->

            runOnUiThread {
                verifyMobileButton.isEnabled = true

                val currentDetailsMatch =
                    organizationCodeInput.text.toString().trim() ==
                        requestedCode &&
                    registeredMobileInput.text.toString().trim() ==
                        requestedMobile &&
                    apiTokenInput.text.toString().trim() ==
                        requestedToken

                if (!currentDetailsMatch) {
                    SecureConfig.clearOrganizationVerification(this)

                    organizationNameText.text =
                        "Organization Name: Not verified"

                    organizationStatusText.text =
                        "Organization Verification: PENDING"

                    connectionStatusText.text =
                        "Details changed. Verify again."

                    updateActivationStatus()
                    return@runOnUiThread
                }

                result.fold(
                    onSuccess = { response ->

                        val name = response
                            .optString("organizationName")
                            .trim()

                        if (name.isBlank() ||
                            !SecureConfig.isOrganizationVerified(this)
                        ) {
                            SecureConfig.clearOrganizationVerification(this)

                            organizationNameText.text =
                                "Organization Name: Not verified"

                            organizationStatusText.text =
                                "Organization Verification: REJECTED"

                            connectionStatusText.text =
                                "Server did not confirm verification."

                            updateActivationStatus()
                            return@fold
                        }

                        getSharedPreferences(
                            statusPrefsName,
                            MODE_PRIVATE
                        ).edit()
                            .putBoolean("organization_verified", true)
                            .putString("organization_status", "VERIFIED")
                            .putString("organization_name", name)
                            .apply()

                        organizationNameText.text =
                            "Organization Name: $name"

                        organizationStatusText.text =
                            "Organization Verification: VERIFIED"

                        connectionStatusText.text =
                            response.optString(
                                "message",
                                "Organization verified successfully."
                            )

                        Toast.makeText(
                            this,
                            "Organization verified successfully.",
                            Toast.LENGTH_LONG
                        ).show()

                        updateActivationStatus()
                    },

                    onFailure = { error ->

                        SecureConfig.clearOrganizationVerification(this)

                        organizationNameText.text =
                            "Organization Name: Not verified"

                        organizationStatusText.text =
                            "Organization Verification: REJECTED"

                        connectionStatusText.text =
                            error.message
                                ?: "Organization verification failed."

                        updateActivationStatus()
                    }
                )
            }
        }
    }

    /**
     * Test whether the official server responds over HTTPS.
     * This tests reachability, not organization authorization.
     */
    private fun testConnection() {
        if (!saveSettings()) return

        testConnectionButton.isEnabled = false
        connectionStatusText.text = "Checking server connection..."

        Thread {
            var connected = false

            val message = try {
                val connection = URL(
                    "${SecureConfig.DEFAULT_SERVER_URL}/"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                connection.useCaches = false
                connection.instanceFollowRedirects = false

                try {
                    val code = connection.responseCode
                    connected = code in 200..299

                    if (connected) {
                        "Server is reachable (HTTP $code)."
                    } else {
                        "Server responded with HTTP $code."
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                "Connection failed. Check internet or server availability."
            }

            runOnUiThread {
                testConnectionButton.isEnabled = true
                connectionStatusText.text = message

                setServerState(
                    if (connected) STATE_CONNECTED else STATE_FAILED
                )

                if (!connected) {
                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Activate only after server verification.
     */
    private fun activateDevice() {
        if (!SecureConfig.isOrganizationVerified(this)) {
            connectionStatusText.text =
                "Verify the Organization Code and registered mobile first."
            return
        }

        if (!saveSettings()) return

        if (!SecureConfig.isOrganizationVerified(this)) {
            connectionStatusText.text =
                "Organization verification is required."
            return
        }

        activateDeviceButton.isEnabled = false
        activateDeviceButton.text = "Activating..."
        connectionStatusText.text = "Requesting device activation..."

        setServerState(STATE_CONNECTING)

        ApiClient.activate { result ->

            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Verify/Connect Device"

                result.fold(
                    onSuccess = {
                        connectionStatusText.text =
                            "Device activated successfully."

                        activationStatusText.text =
                            "Device status: ACTIVATED"

                        setServerState(STATE_CONNECTED)

                        Toast.makeText(
                            this,
                            "Device activated successfully.",
                            Toast.LENGTH_LONG
                        ).show()
                    },

                    onFailure = { error ->
                        SecureConfig.clearDeviceToken(this)

                        connectionStatusText.text =
                            error.message ?: "Device activation failed."

                        activationStatusText.text =
                            "Device status: NOT ACTIVATED"

                        setServerState(STATE_FAILED)

                        Toast.makeText(
                            this,
                            connectionStatusText.text,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )

                updateActivationStatus()
            }
        }
    }

    private fun updateSimStatus() {
        simStatusText.text =
            "SIM status: Checked by the SMS sending service"
    }

    private fun updateActivationStatus() {
        val verified =
            SecureConfig.isOrganizationVerified(this)

        val deviceToken =
            SecureConfig.getDeviceToken(this)

        val apiToken =
            SecureConfig.getCompanyApiKey(this)

        val deviceCode =
            SecureConfig.getDeviceCode(this)

        if (
            !verified ||
            deviceToken.isNullOrBlank() ||
            apiToken.isNullOrBlank() ||
            deviceCode.isBlank()
        ) {
            activationStatusText.text =
                "Device status: NOT ACTIVATED"

            return
        }

        activationStatusText.text =
            "Device status: ACTIVATED"
    }

    private fun setServerState(state: String) {
        getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        ).edit()
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
            loadSettings()
        }
    }
}
