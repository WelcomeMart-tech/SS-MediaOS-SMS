package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.view.View
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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
    private lateinit var verifyMobileButton: Button
    private lateinit var connectionStatusText: TextView
    private lateinit var activationStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var activateDeviceButton: Button

    private val statusPrefsName = "ss_mediaos_gateway_status"

    companion object {
        private const val CONNECTION_TIMEOUT = 15000
        private const val ACTIVATION_READ_TIMEOUT = 20000

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
        verifyMobileButton = findViewById(R.id.verifyMobileButton)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        activationStatusText = findViewById(R.id.activationStatusText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)

        // Server URL and Device ID are controlled internally.
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.visibility = View.GONE // Server URL is never shown in the UI.

        val deviceCode = SecureConfig.getDeviceCode(this).orEmpty()
        deviceIdInput.setText(deviceCode)
        deviceIdInput.isEnabled = false

        loadSettings()

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testConnectionButton.setOnClickListener {
            testConnection()
        }

        activateDeviceButton.setOnClickListener {
            activateDevice()
        }

        verifyMobileButton.setOnClickListener {
            verifyOrganizationAndMobile()
        }

        updateSimStatusPlaceholder()
    }

    private fun loadSettings() {
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.visibility = View.GONE

        // The XML input should use textPassword so the API key is masked.
        apiTokenInput.setText(
            SecureConfig.getCompanyApiKey(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this).orEmpty()
        )

        val prefs = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
        organizationCodeInput.setText(prefs.getString("organization_code", "").orEmpty())
        registeredMobileInput.setText(prefs.getString("registered_mobile", "").orEmpty())
        organizationNameText.text = prefs.getString("organization_name", null)
            ?.takeIf { it.isNotBlank() } ?: "Organization Name: Not verified"
        organizationStatusText.text = prefs.getString("organization_status", null)
            ?.takeIf { it.isNotBlank() } ?: "Organization Verification: PENDING"

        updateActivationStatus()
    }

    private fun saveSettings(): Boolean {
        val companyApiKey = apiTokenInput.text.toString().trim()
        val deviceCode = SecureConfig.getDeviceCode(this).orEmpty()
        val organizationCode = organizationCodeInput.text.toString().trim()
        val registeredMobile = registeredMobileInput.text.toString().trim()

        if (companyApiKey.isBlank()) {
            apiTokenInput.error = "Company API Token required"
            apiTokenInput.requestFocus()
            return false
        }

        if (organizationCode.isBlank()) {
            organizationCodeInput.error = "Organization Code required"
            organizationCodeInput.requestFocus()
            return false
        }

        if (registeredMobile.isBlank()) {
            registeredMobileInput.error = "Registered mobile number required"
            registeredMobileInput.requestFocus()
            return false
        }

        if (registeredMobile.any { !it.isDigit() } || registeredMobile.length !in 8..15) {
            registeredMobileInput.error = "Enter a valid mobile number (8–15 digits)"
            registeredMobileInput.requestFocus()
            return false
        }

        if (deviceCode.isBlank()) {
            connectionStatusText.text =
                "Unable to create this installation's Device ID."
            return false
        }

        return try {
            val oldApiKey = SecureConfig.getCompanyApiKey(this).orEmpty()

            val saved = SecureConfig.saveActivationSettings(
                context = this,
                serverUrl = SecureConfig.DEFAULT_SERVER_URL,
                deviceCode = deviceCode,
                activationToken = companyApiKey
            )

            if (!saved) {
                connectionStatusText.text =
                    "Settings could not be saved. Please check the Company API Token."
                return false
            }

            if (oldApiKey != companyApiKey) {
                SecureConfig.clearDeviceToken(this)
                setServerState(STATE_CONNECTING)
            }

            val prefs = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
            val oldOrganizationCode = prefs.getString("organization_code", "").orEmpty()
            val oldRegisteredMobile = prefs.getString("registered_mobile", "").orEmpty()
            val identityChanged =
                oldOrganizationCode != organizationCode ||
                oldRegisteredMobile != registeredMobile ||
                oldApiKey != companyApiKey

            val editor = prefs.edit()
                .putString("organization_code", organizationCode)
                .putString("registered_mobile", registeredMobile)
            if (identityChanged) {
                editor.putString("organization_status", "PENDING")
                    .remove("organization_name")
                    .putBoolean("organization_verified", false)
                SecureConfig.clearDeviceToken(this)
                organizationNameText.text = "Organization Name: Not verified"
                organizationStatusText.text = "Organization Verification: PENDING"
            }
            editor.apply()

            updateActivationStatus()

            Toast.makeText(
                this,
                "Company API Token saved successfully",
                Toast.LENGTH_SHORT
            ).show()

            true
        } catch (e: Exception) {
            connectionStatusText.text =
                "Settings could not be saved. Please try again."
            false
        }
    }

    private fun testConnection() {
        if (!saveSettings()) return

        connectionStatusText.text = "Checking server connection..."
        testConnectionButton.isEnabled = false

        Thread {
            var reachable = false

            val resultText = try {
                val connection = URL(
                    "${SecureConfig.DEFAULT_SERVER_URL}/"
                ).openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                connection.useCaches = false
                connection.instanceFollowRedirects = true

                try {
                    val statusCode = connection.responseCode
                    reachable = statusCode in 200..299

                    if (reachable) {
                        "Server is reachable (HTTP $statusCode)."
                    } else {
                        "Server responded with HTTP $statusCode."
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                "Connection failed. Check your internet connection and try again."
            }

            runOnUiThread {
                connectionStatusText.text = resultText

                if (!reachable) {
                    setServerState(STATE_FAILED)
                }

                testConnectionButton.isEnabled = true
                updateActivationStatus()
            }
        }.start()
    }

    private fun activateDevice() {
        if (!saveSettings()) return

        activateDeviceButton.isEnabled = false
        activateDeviceButton.text = "Connecting..."
        activationStatusText.text =
            "Device status: ACTIVATION IN PROGRESS"
        connectionStatusText.text =
            "Connecting securely to SS MediaOS..."

        val serverUrl = SecureConfig.DEFAULT_SERVER_URL
        val deviceCode = SecureConfig.getDeviceCode(this).orEmpty()
        val companyApiKey = SecureConfig.getCompanyApiKey(this).orEmpty()
        val prefs = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
        val organizationVerified = prefs.getBoolean("organization_verified", false)

        if (!organizationVerified) {
            activateDeviceButton.isEnabled = true
            activateDeviceButton.text = "Verify/Connect Device"
            connectionStatusText.text =
                "Verify the Organization Code and registered mobile number first."
            return
        }

        if (deviceCode.isBlank() || companyApiKey.isBlank()) {
            activateDeviceButton.isEnabled = true
            activateDeviceButton.text = "Verify/Connect Device"
            connectionStatusText.text =
                "Device ID or Company API Token is missing."
            return
        }

        setServerState(STATE_CONNECTING)

        Thread {
            var success = false
            var message = "Activation failed."

            try {
                val endpoint =
                    "$serverUrl/api/v1/sms/device_activate.php"

                val connection = URL(endpoint)
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = ACTIVATION_READ_TIMEOUT
                connection.doOutput = true
                connection.useCaches = false

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )
                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val organizationCode = organizationCodeInput.text.toString().trim()
                val registeredMobile = registeredMobileInput.text.toString().trim()

                val requestBody = JSONObject().apply {
                    put("deviceCode", deviceCode)
                    put("companyApiKey", companyApiKey)
                    put("organizationCode", organizationCode)
                    put("registeredMobileNumber", registeredMobile)
                }.toString()

                try {
                    OutputStreamWriter(
                        connection.outputStream,
                        Charsets.UTF_8
                    ).use { writer ->
                        writer.write(requestBody)
                        writer.flush()
                    }

                    val statusCode = connection.responseCode

                    val responseStream = if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                    val responseText = responseStream?.use { stream ->
                        BufferedReader(
                            InputStreamReader(stream, Charsets.UTF_8)
                        ).use { reader ->
                            reader.readText()
                        }
                    }.orEmpty()

                    val response = try {
                        JSONObject(responseText)
                    } catch (_: Exception) {
                        JSONObject()
                    }

                    val serverMessage = response.optString(
                        "message",
                        "Server returned HTTP $statusCode"
                    )

                    val deviceToken = response.optString(
                        "deviceToken",
                        ""
                    ).trim()

                    if (
                        statusCode in 200..299 &&
                        response.optBoolean("ok", false) &&
                        deviceToken.isNotBlank()
                    ) {
                        if (SecureConfig.saveDeviceToken(this, deviceToken)) {
                            success = true
                            message = "Device activated successfully."
                        } else {
                            SecureConfig.clearDeviceToken(this)
                            message =
                                "Activation succeeded, but the device token could not be saved."
                        }
                    } else {
                        message = serverMessage

                        if (statusCode !in 200..299) {
                            message += " (HTTP $statusCode)"
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                message =
                    "Activation failed. Check your connection and try again."
            }

            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Verify/Connect Device"
                connectionStatusText.text = message

                if (success) {
                    activationStatusText.text = "Device status: ACTIVATED"

                    Toast.makeText(
                        this,
                        "Device activated successfully",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    SecureConfig.clearDeviceToken(this)
                    setServerState(STATE_FAILED)

                    activationStatusText.text =
                        "Device status: NOT ACTIVATED"

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }

                updateActivationStatus()
            }
        }.start()
    }

    /**
     * Calls the organization verification API. The server endpoint must validate
     * the Company API Key, Organization Code and registered mobile number against
     * authoritative server records and return { ok:true, organizationName:"..." }.
     */
    private fun verifyOrganizationAndMobile() {
        val companyApiKey = apiTokenInput.text.toString().trim()
        val organizationCode = organizationCodeInput.text.toString().trim()
        val registeredMobile = registeredMobileInput.text.toString().trim()

        if (companyApiKey.isBlank()) {
            apiTokenInput.error = "Company API Key required"
            apiTokenInput.requestFocus()
            return
        }
        if (organizationCode.isBlank()) {
            organizationCodeInput.error = "Organization Code required"
            organizationCodeInput.requestFocus()
            return
        }
        if (registeredMobile.any { !it.isDigit() } || registeredMobile.length !in 8..15) {
            registeredMobileInput.error = "Enter a valid mobile number (8–15 digits)"
            registeredMobileInput.requestFocus()
            return
        }

        // Capture the exact identity being verified. A response for old details
        // must never verify details the user changed while the request was running.
        val requestedApiKey = companyApiKey
        val requestedOrganizationCode = organizationCode
        val requestedRegisteredMobile = registeredMobile

        // Fail closed while checking: discard any old verification/device token.
        getSharedPreferences(statusPrefsName, MODE_PRIVATE).edit()
            .putBoolean("organization_verified", false)
            .putString("organization_status", "CHECKING")
            .remove("organization_name")
            .apply()
        SecureConfig.clearDeviceToken(this)
        organizationNameText.text = "Organization Name: Not verified"
        organizationStatusText.text = "Organization Verification: CHECKING"
        activationStatusText.text = "Device status: NOT ACTIVATED"

        verifyMobileButton.isEnabled = false
        organizationStatusText.text = "Organization Verification: CHECKING"
        organizationNameText.text = "Organization Name: Checking..."
        connectionStatusText.text = "Verifying organization and registered mobile..."

        Thread {
            var verified = false
            var organizationName = ""
            var message = "Organization verification failed."

            try {
                val endpoint =
                    "${SecureConfig.DEFAULT_SERVER_URL}/api/v1/sms/organization_verify.php"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = ACTIVATION_READ_TIMEOUT
                connection.doOutput = true
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $companyApiKey")

                try {
                    val body = JSONObject()
                        .put("companyApiKey", companyApiKey)
                        .put("organizationCode", organizationCode)
                        .put("registeredMobileNumber", registeredMobile)
                        .toString()

                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                        it.write(body)
                        it.flush()
                    }

                    val statusCode = connection.responseCode
                    val stream = if (statusCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val responseText = stream?.use {
                        BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader ->
                            reader.readText()
                        }
                    }.orEmpty()
                    val response = try { JSONObject(responseText) } catch (_: Exception) {
                        JSONObject()
                    }

                    verified = statusCode in 200..299 &&
                        response.optBoolean("ok", false) &&
                        response.optString("organizationName").isNotBlank()

                    organizationName = response.optString("organizationName").trim()
                    message = response.optString(
                        "message",
                        if (verified) "Organization verified successfully."
                        else "Server could not verify this organization and mobile number."
                    )
                    if (statusCode !in 200..299) message += " (HTTP $statusCode)"
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                message =
                    "Verification API unavailable. Confirm the server endpoint and internet connection."
            }

            runOnUiThread {
                verifyMobileButton.isEnabled = true

                // Ignore stale server responses if any identity field changed in flight.
                val currentApiKey = apiTokenInput.text.toString().trim()
                val currentOrganizationCode = organizationCodeInput.text.toString().trim()
                val currentRegisteredMobile = registeredMobileInput.text.toString().trim()
                val identityStillMatches =
                    currentApiKey == requestedApiKey &&
                    currentOrganizationCode == requestedOrganizationCode &&
                    currentRegisteredMobile == requestedRegisteredMobile

                if (!identityStillMatches) {
                    val prefs = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("organization_verified", false)
                        .putString("organization_status", "PENDING")
                        .remove("organization_name")
                        .apply()
                    SecureConfig.clearDeviceToken(this)
                    organizationNameText.text = "Organization Name: Not verified"
                    organizationStatusText.text = "Organization Verification: PENDING"
                    connectionStatusText.text =
                        "Details changed during verification. Verify again with the current details."
                    updateActivationStatus()
                    return@runOnUiThread
                }

                val prefs = getSharedPreferences(statusPrefsName, MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("organization_verified", verified)
                    .putString("organization_status", if (verified) "VERIFIED" else "REJECTED")
                    .apply()

                if (verified) {
                    prefs.edit().putString("organization_name", organizationName).apply()
                    organizationNameText.text = "Organization Name: $organizationName"
                    organizationStatusText.text = "Organization Verification: VERIFIED"
                } else {
                    prefs.edit().remove("organization_name").apply()
                    organizationNameText.text = "Organization Name: Not verified"
                    organizationStatusText.text = "Organization Verification: REJECTED"
                    SecureConfig.clearDeviceToken(this)
                }
                connectionStatusText.text = message
                updateActivationStatus()
            }
        }.start()
    }

    private fun updateSimStatusPlaceholder() {
        // Actual SIM/subscription selection belongs in SmsHelper and needs runtime
        // permission checks. Do not claim a SIM is verified before that integration.
        simStatusText.text =
            "SIM status: Will be checked by the SMS sending service"
    }

    private fun updateActivationStatus() {
        val deviceToken = SecureConfig.getDeviceToken(this)
        val companyApiKey = SecureConfig.getCompanyApiKey(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val organizationVerified = getSharedPreferences(
            statusPrefsName, MODE_PRIVATE
        ).getBoolean("organization_verified", false)

        if (
            deviceToken.isNullOrBlank() ||
            companyApiKey.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            !organizationVerified
        ) {
            activationStatusText.text = "Device status: NOT ACTIVATED"

            if (connectionStatusText.text.isNullOrBlank()) {
                connectionStatusText.text =
                    "Enter the Company API Token and activate this device."
            }
            return
        }

        activationStatusText.text = "Device status: ACTIVATED"

        val prefs = getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        )

        val serviceRunning = prefs.getBoolean(
            PREF_SERVICE_RUNNING,
            false
        )

        val serverState = prefs.getString(
            PREF_SERVER_STATE,
            STATE_CONNECTING
        ) ?: STATE_CONNECTING

        val lastCheckAt = prefs.getLong(
            PREF_SERVER_CHECK_AT,
            0L
        )

        val isRecent = lastCheckAt > 0L &&
            System.currentTimeMillis() - lastCheckAt <= 30000L

        when {
            !serviceRunning -> {
                connectionStatusText.text =
                    "Device activated. Gateway service is not running."
            }

            !isRecent -> {
                connectionStatusText.text =
                    "Device activated. Waiting for a fresh server connection check."
            }

            serverState == STATE_CONNECTED -> {
                connectionStatusText.text =
                    "Device activated. Server connection confirmed by gateway polling."
            }

            serverState == STATE_FAILED -> {
                connectionStatusText.text =
                    "Device activated, but server connection failed. Retrying..."
            }

            else -> {
                connectionStatusText.text =
                    "Device activated. Connecting to server..."
            }
        }
    }

    private fun setServerState(state: String) {
        getSharedPreferences(
            statusPrefsName,
            MODE_PRIVATE
        ).edit()
            .putString(PREF_SERVER_STATE, state)
            .putLong(PREF_SERVER_CHECK_AT, System.currentTimeMillis())
            .apply()
    }

    override fun onResume() {
        super.onResume()

        if (::activationStatusText.isInitialized) {
            updateActivationStatus()
        }
    }
}
