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
        serverUrlInput.setText(
            SecureConfig.getServerUrl(this).orEmpty()
        )

        apiTokenInput.setText(
            SecureConfig.getActivationToken(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceId(this).orEmpty()
        )

        updateActivationStatus()
    }

    private fun saveSettings(): Boolean {
        val serverUrl = serverUrlInput.text.toString()
            .trim()
            .trimEnd('/')

        val activationToken = apiTokenInput.text.toString().trim()
        val deviceId = deviceIdInput.text.toString().trim()

        if (serverUrl.isBlank()) {
            serverUrlInput.error = "Server URL required"
            serverUrlInput.requestFocus()
            return false
        }

        if (!serverUrl.startsWith("https://", ignoreCase = true)) {
            serverUrlInput.error = "HTTPS URL required"
            serverUrlInput.requestFocus()
            return false
        }

        if (activationToken.isBlank()) {
            apiTokenInput.error = "Activation token required"
            apiTokenInput.requestFocus()
            return false
        }

        if (deviceId.isBlank()) {
            deviceIdInput.error = "Device ID required"
            deviceIdInput.requestFocus()
            return false
        }

        return try {
            val previousServerUrl = SecureConfig.getServerUrl(this)
                .orEmpty()
                .trim()
                .trimEnd('/')

            val previousDeviceId = SecureConfig.getDeviceId(this)
                .orEmpty()
                .trim()

            val settingsChanged =
                previousServerUrl != serverUrl ||
                previousDeviceId != deviceId

            val saved = SecureConfig.saveActivationSettings(
                context = this,
                serverUrl = serverUrl,
                deviceCode = deviceId,
                activationToken = activationToken
            )

            if (!saved) {
                connectionStatusText.text =
                    "Settings could not be saved. Check the server URL."
                return false
            }

            if (settingsChanged) {
                SecureConfig.clearDeviceToken(this)
                setServerState(STATE_CONNECTING)
            }

            updateActivationStatus()

            Toast.makeText(
                this,
                "Settings saved successfully",
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

        val serverUrl = serverUrlInput.text.toString()
            .trim()
            .trimEnd('/')

        Thread {
            var reachable = false

            val resultText = try {
                val connection = URL("$serverUrl/")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECTION_TIMEOUT
                connection.readTimeout = CONNECTION_TIMEOUT
                connection.useCaches = false
                connection.instanceFollowRedirects = true

                try {
                    val statusCode = connection.responseCode

                    reachable = statusCode in 200..299

                    if (reachable) {
                        "Server is reachable (HTTP $statusCode). " +
                            "Device activation and gateway polling are checked separately."
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
        activateDeviceButton.text = "Activating..."
        activationStatusText.text = "Device status: ACTIVATION IN PROGRESS"
        connectionStatusText.text = "Sending activation request to server..."

        val serverUrl = serverUrlInput.text.toString()
            .trim()
            .trimEnd('/')

        val deviceCode = deviceIdInput.text.toString().trim()
        val activationToken = apiTokenInput.text.toString().trim()

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
                    put("activationToken", activationToken)
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
                        val savedToken = SecureConfig.saveDeviceToken(
                            this,
                            deviceToken
                        )

                        if (savedToken) {
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
                message = "Activation error: ${e.message ?: "Network error"}"
            }

            runOnUiThread {
                activateDeviceButton.isEnabled = true
                activateDeviceButton.text = "Activate Device"
                connectionStatusText.text = message

                if (success) {
                    activationStatusText.text =
                        "Device status: ACTIVATED"

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
        val activationToken = SecureConfig.getActivationToken(this)
        val deviceId = SecureConfig.getDeviceId(this)
        val serverUrl = SecureConfig.getServerUrl(this)

        if (
            deviceToken.isNullOrBlank() ||
            activationToken.isNullOrBlank() ||
            deviceId.isNullOrBlank() ||
            serverUrl.isNullOrBlank()
        ) {
            activationStatusText.text =
                "Device status: NOT ACTIVATED"

            if (connectionStatusText.text.isNullOrBlank()) {
                connectionStatusText.text =
                    "Server settings and device activation are required."
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

        val currentTime = System.currentTimeMillis()
        val isRecent = lastCheckAt > 0L &&
            currentTime - lastCheckAt <= 30000L

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
