package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
        connectionStatusText = findViewById(R.id.connectionStatusText)
        activationStatusText = findViewById(R.id.activationStatusText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)

        // Server URL and Device ID are controlled internally.
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.isEnabled = false

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
    }

    private fun loadSettings() {
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)

        // The XML input should use textPassword so the API key is masked.
        apiTokenInput.setText(
            SecureConfig.getCompanyApiKey(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this).orEmpty()
        )

        updateActivationStatus()
    }

    private fun saveSettings(): Boolean {
        val companyApiKey = apiTokenInput.text.toString().trim()
        val deviceCode = SecureConfig.getDeviceCode(this).orEmpty()

        if (companyApiKey.isBlank()) {
            apiTokenInput.error = "Company API Token required"
            apiTokenInput.requestFocus()
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

            updateActivationStatus()

            Toast.makeText(
                this,
                "Company API Token saved successfully",
                Toast.LENGTH_SHORT
            ).show()

            true
        } catch (e: Exception) {
            connectionStatusText.text =
                "Settings save failed: ${e.message ?: "Unknown error"}"
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
                "Connection failed: ${e.message ?: "Network error"}"
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

        if (deviceCode.isBlank() || companyApiKey.isBlank()) {
            activateDeviceButton.isEnabled = true
            activateDeviceButton.text = "Connect Device"
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

                val requestBody = JSONObject().apply {
                    put("deviceCode", deviceCode)
                    put("companyApiKey", companyApiKey)
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
                    "Activation error: ${e.message ?: "Network error"}"
            }

            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Connect Device"
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

    private fun updateActivationStatus() {
        val deviceToken = SecureConfig.getDeviceToken(this)
        val companyApiKey = SecureConfig.getCompanyApiKey(this)
        val deviceCode = SecureConfig.getDeviceCode(this)

        if (
            deviceToken.isNullOrBlank() ||
            companyApiKey.isNullOrBlank() ||
            deviceCode.isNullOrBlank()
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
