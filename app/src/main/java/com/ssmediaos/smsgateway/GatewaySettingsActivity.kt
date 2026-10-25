
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
import kotlin.concurrent.thread

class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var connectionStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)

        bindViews()
        loadSavedSettings()
        setupListeners()
    }

    private fun bindViews() {
        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
    }

    private fun loadSavedSettings() {
        serverUrlInput.setText(
            SecureConfig.getServerUrl(this).orEmpty()
        )

        apiTokenInput.setText(
            SecureConfig.getApiToken(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceId(this).orEmpty()
        )

        connectionStatusText.text = "Enter your server details and save settings."
    }

    private fun setupListeners() {
        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testConnectionButton.setOnClickListener {
            testServerConnection()
        }
    }

    private fun saveSettings(): Boolean {
        val serverUrl = serverUrlInput.text.toString().trim()
        val apiToken = apiTokenInput.text.toString().trim()
        val deviceId = deviceIdInput.text.toString().trim()

        if (serverUrl.isBlank()) {
            serverUrlInput.error = "Enter Server URL"
            serverUrlInput.requestFocus()
            return false
        }

        if (apiToken.isBlank()) {
            apiTokenInput.error = "Enter API Token"
            apiTokenInput.requestFocus()
            return false
        }

        if (deviceId.isBlank()) {
            deviceIdInput.error = "Enter Device ID"
            deviceIdInput.requestFocus()
            return false
        }

        val saved = SecureConfig.saveSettings(
            context = this,
            serverUrl = serverUrl,
            apiToken = apiToken,
            deviceId = deviceId
        )

        if (!saved) {
            connectionStatusText.text =
                "Could not save settings. Check the Server URL and try again."

            Toast.makeText(
                this,
                "Invalid settings. Check Server URL, API Token and Device ID.",
                Toast.LENGTH_LONG
            ).show()

            return false
        }

        connectionStatusText.text = "Settings saved successfully."

        Toast.makeText(
            this,
            "Gateway settings saved.",
            Toast.LENGTH_SHORT
        ).show()

        return true
    }

    private fun testServerConnection() {
        val serverUrl = serverUrlInput.text.toString().trim().trimEnd('/')

        if (serverUrl.isBlank()) {
            serverUrlInput.error = "Enter Server URL"
            serverUrlInput.requestFocus()
            return
        }

        if (!serverUrl.startsWith("https://", ignoreCase = true) &&
            !serverUrl.startsWith("http://", ignoreCase = true)
        ) {
            serverUrlInput.error = "URL must start with http:// or https://"
            serverUrlInput.requestFocus()
            return
        }

        connectionStatusText.text = "Checking server connection…"
        testConnectionButton.isEnabled = false

        thread(name = "server-connection-test") {
            var connection: HttpURLConnection? = null

            val result = try {
                connection = URL(serverUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty(
                    "User-Agent",
                    "SS-MediaOS-SMS-Gateway/1.0"
                )

                val responseCode = connection.responseCode

                when (responseCode) {
                    in 200..299 ->
                        "Server is reachable. HTTP $responseCode"

                    401, 403 ->
                        "Server is reachable, but access was denied (HTTP $responseCode). API authentication needs checking."

                    404 ->
                        "Server is reachable, but the base path returned HTTP 404. Check the server URL or API endpoint."

                    else ->
                        "Server responded with HTTP $responseCode."
                }
            } catch (e: Exception) {
                "Connection failed: ${
                    e.localizedMessage ?: "Check URL, internet and server"
                }"
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                connectionStatusText.text = result
                testConnectionButton.isEnabled = true
            }
        }
    }
}

