package com.ssmediaos.smsgateway

import android.app.Activity
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var connectionStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button

    @Volatile
    private var connectionTestRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)

        bindViews()
        configureFieldLabels()
        loadSavedSettings()
        setupListeners()
    }

    private fun bindViews() {
        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        connectionStatusText =
            findViewById(R.id.connectionStatusText)
        saveSettingsButton =
            findViewById(R.id.saveSettingsButton)
        testConnectionButton =
            findViewById(R.id.testConnectionButton)
    }

    private fun configureFieldLabels() {
        serverUrlInput.hint = "HTTPS Server URL"
        deviceIdInput.hint = "SS MediaOS Device Code"
        apiTokenInput.hint = "Activation Token"

        apiTokenInput.transformationMethod =
            PasswordTransformationMethod.getInstance()
    }

    private fun loadSavedSettings() {
        serverUrlInput.setText(
            SecureConfig.getServerUrl(this).orEmpty()
        )

        apiTokenInput.setText(
            SecureConfig.getActivationToken(this).orEmpty()
        )

        deviceIdInput.setText(
            SecureConfig.getDeviceCode(this).orEmpty()
        )

        connectionStatusText.text =
            "Enter the HTTPS server URL, then test the connection."
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
        val serverUrl =
            serverUrlInput.text.toString().trim().trimEnd('/')

        val activationToken =
            apiTokenInput.text.toString().trim()

        val deviceCode =
            deviceIdInput.text.toString().trim()

        if (!isValidHttpsUrl(serverUrl)) {
            serverUrlInput.error =
                "Enter a valid HTTPS server URL"
            serverUrlInput.requestFocus()
            return false
        }

        if (deviceCode.isBlank()) {
            deviceIdInput.error = "Enter Device Code"
            deviceIdInput.requestFocus()
            return false
        }

        if (activationToken.isBlank()) {
            apiTokenInput.error = "Enter Activation Token"
            apiTokenInput.requestFocus()
            return false
        }

        val saved = SecureConfig.saveActivationSettings(
            context = this,
            serverUrl = serverUrl,
            deviceCode = deviceCode,
            activationToken = activationToken
        )

        if (!saved) {
            connectionStatusText.text =
                "Settings could not be saved. Check the entered values."

            Toast.makeText(
                this,
                "Could not save gateway settings.",
                Toast.LENGTH_LONG
            ).show()

            return false
        }

        connectionStatusText.text =
            "Settings saved. You can now activate the device."

        Toast.makeText(
            this,
            "Gateway settings saved.",
            Toast.LENGTH_SHORT
        ).show()

        return true
    }

    private fun testServerConnection() {
        val serverUrl =
            serverUrlInput.text.toString().trim().trimEnd('/')

        if (!isValidHttpsUrl(serverUrl)) {
            serverUrlInput.error =
                "Enter a valid HTTPS server URL"
            serverUrlInput.requestFocus()
            return
        }

        if (connectionTestRunning) return

        connectionTestRunning = true
        testConnectionButton.isEnabled = false
        saveSettingsButton.isEnabled = false

        connectionStatusText.text =
            "Checking HTTPS server connection..."

        thread(name = "ssmediaos-server-test") {
            var connection: HttpURLConnection? = null

            val result = try {
                connection = URL("$serverUrl/").openConnection()
                    as HttpURLConnection

                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true

                    setRequestProperty(
                        "Accept",
                        "text/html,application/json,*/*"
                    )

                    setRequestProperty(
                        "User-Agent",
                        "SS-MediaOS-SMS-Gateway/1.0"
                    )
                }

                val responseCode = connection.responseCode

                when (responseCode) {
                    in 200..299 ->
                        "HTTPS server is reachable (HTTP $responseCode). " +
                            "This test does not verify API authentication."

                    in 300..399 ->
                        "Server returned HTTP $responseCode. " +
                            "Check the HTTPS URL and redirects."

                    in 400..499 ->
                        "HTTPS server responded with HTTP $responseCode. " +
                            "The server is reachable, but rejected this request."

                    in 500..599 ->
                        "HTTPS server is reachable, but returned " +
                            "HTTP $responseCode."

                    else ->
                        "Server responded with HTTP $responseCode."
                }
            } catch (e: Exception) {
                val detail = e.localizedMessage
                    ?.takeIf { it.isNotBlank() }
                    ?: e.javaClass.simpleName

                "Connection failed (${e.javaClass.simpleName}): $detail"
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                connectionStatusText.text = result
                testConnectionButton.isEnabled = true
                saveSettingsButton.isEnabled = true
                connectionTestRunning = false
            }
        }
    }

    private fun isValidHttpsUrl(value: String): Boolean {
        if (value.isBlank()) return false

        return try {
            val uri = URI(value)

            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        } catch (_: Exception) {
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
