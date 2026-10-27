package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var connectionStatusText: TextView
    private lateinit var activationStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var activateDeviceButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)

        activationStatusText = findViewById(R.id.activationStatusText)

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
        val serverUrl = serverUrlInput.text.toString().trim()
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

        connectionStatusText.text = "Checking server connection…"
        testConnectionButton.isEnabled = false

        val serverUrl = serverUrlInput.text.toString()
            .trim()
            .trimEnd('/')

        Thread {
            val resultText = try {
                val connection = URL("$serverUrl/")
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false

                try {
                    val statusCode = connection.responseCode

                    if (statusCode in 200..299) {
                        "Server connection successful (HTTP $statusCode)"
                    } else {
                        "Server responded with HTTP $statusCode"
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                "Connection failed: ${e.message ?: "Network error"}"
            }

            runOnUiThread {
                connectionStatusText.text = resultText
                testConnectionButton.isEnabled = true
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

        Thread {
            var success = false
            var message = "Activation failed."

            try {
                val endpoint =
                    "$serverUrl/api/v1/sms/device_activate.php"

                val connection = URL(endpoint)
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 20000
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
                    activationStatusText.text =
                        "Device status: NOT ACTIVATED"

                    Toast.makeText(
                        this,
                        message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun updateActivationStatus() {
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (deviceToken.isNullOrBlank()) {
            activationStatusText.text =
                "Device status: NOT ACTIVATED"

            connectionStatusText.text =
                "Device activation is required."
        } else {
            activationStatusText.text =
                "Device status: ACTIVATED"

            connectionStatusText.text =
                "Device token is saved on this device."
        }
    }
}
