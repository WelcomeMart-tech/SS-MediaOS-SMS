package com.ssmediaos.smsgateway

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.concurrent.thread

/**
 * Gateway configuration screen.
 *
 * The Main Server URL and Device Code are controlled by SecureConfig. The
 * Company API Key is entered by the organization and stored encrypted by
 * SecureConfig. This screen never displays or transmits the secret in logs.
 */
class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var connectionStatusText: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var organizationInfoText: TextView

    @Volatile private var connectionTestRunning = false

    private val navy = Color.rgb(16, 27, 53)
    private val blue = Color.rgb(24, 119, 242)
    private val green = Color.rgb(22, 163, 74)
    private val backgroundColor = Color.rgb(244, 247, 252)
    private val textDark = Color.rgb(30, 41, 59)
    private val textMuted = Color.rgb(100, 116, 139)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the existing XML resource and IDs so this file is a drop-in
        // replacement and does not require changing the Activity manifest.
        setContentView(R.layout.activity_gateway_settings)
        bindViews()
        configureFields()
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

        // Optional view: older layout versions do not contain this ID.
        val infoId = resources.getIdentifier(
            "organizationInfoText", "id", packageName
        )
        if (infoId != 0) organizationInfoText = findViewById(infoId)
    }

    private fun configureFields() {
        serverUrlInput.hint = "Main Server HTTPS URL"
        serverUrlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        serverUrlInput.isFocusable = false
        serverUrlInput.isFocusableInTouchMode = false
        serverUrlInput.isClickable = false
        serverUrlInput.contentDescription = "Main Server URL, managed by the application"

        deviceIdInput.hint = "Generated Gateway Device Code"
        deviceIdInput.inputType = InputType.TYPE_CLASS_TEXT
        deviceIdInput.isFocusable = false
        deviceIdInput.isFocusableInTouchMode = false
        deviceIdInput.isClickable = false
        deviceIdInput.contentDescription = "Generated device code, managed by the application"

        apiTokenInput.hint = "Company API Key"
        apiTokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        apiTokenInput.transformationMethod = PasswordTransformationMethod.getInstance()
        apiTokenInput.contentDescription = "Company API Key. Stored encrypted on this device."
    }

    private fun loadSavedSettings() {
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        updateOrganizationSummary()
        connectionStatusText.text =
            "Configuration loaded. Save the Company API Key, then test the Main Server connection."
    }

    private fun setupListeners() {
        saveSettingsButton.setOnClickListener { saveSettings() }
        testConnectionButton.setOnClickListener { testServerConnection() }
    }

    private fun saveSettings(): Boolean {
        val apiKey = apiTokenInput.text?.toString()?.trim().orEmpty()
        val deviceCode = SecureConfig.getDeviceCode(this)

        if (apiKey.isBlank()) {
            apiTokenInput.error = "Enter the Company API Key"
            apiTokenInput.requestFocus()
            return false
        }
        if (apiKey.length < 16) {
            apiTokenInput.error = "Check the Company API Key"
            apiTokenInput.requestFocus()
            return false
        }

        val saved = SecureConfig.saveActivationSettings(
            context = this,
            serverUrl = SecureConfig.DEFAULT_SERVER_URL,
            deviceCode = deviceCode,
            activationToken = apiKey
        )

        if (!saved) {
            setStatus("Could not securely save the Company API Key. Check device security and try again.", false)
            Toast.makeText(this, "Settings could not be saved.", Toast.LENGTH_LONG).show()
            return false
        }

        // Never echo the API key into a status message.
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        setStatus("Company API Key saved securely. Server-side verification is still required.", true)
        Toast.makeText(this, "Gateway settings saved securely.", Toast.LENGTH_SHORT).show()
        updateOrganizationSummary()
        return true
    }

    private fun testServerConnection() {
        val serverUrl = SecureConfig.DEFAULT_SERVER_URL.trimEnd('/')
        if (!isValidHttpsUrl(serverUrl)) {
            setStatus("The built-in Main Server URL is invalid. Contact support.", false)
            return
        }
        if (connectionTestRunning) return

        connectionTestRunning = true
        testConnectionButton.isEnabled = false
        saveSettingsButton.isEnabled = false
        setStatus("Connecting to Main Server over HTTPS…", null)

        thread(name = "ssmediaos-main-server-connectivity") {
            var connection: HttpURLConnection? = null
            val result = try {
                connection = (URL("$serverUrl/").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SS-MediaOS-SMS-Gateway/1.0")
                }
                val code = connection.responseCode
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                stream?.use { input ->
                    val buffer = ByteArray(1024)
                    var total = 0
                    while (total < 8192) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                    }
                }
                when (code) {
                    in 200..299 -> "Main Server reachable (HTTP $code). This is a connectivity test, not API-key or device activation verification."
                    in 300..399 -> "Main Server responded with redirect (HTTP $code). Check the configured endpoint."
                    in 400..499 -> "Main Server reachable, but returned HTTP $code. API authorization has not been tested."
                    in 500..599 -> "Main Server reachable, but reported server error HTTP $code."
                    else -> "Main Server responded with HTTP $code."
                }
            } catch (e: Exception) {
                "Connection failed: ${e.localizedMessage ?: "Check internet connectivity."}"
            } finally {
                connection?.disconnect()
            }

            runOnUiThread {
                connectionStatusText.text = result
                connectionStatusText.setTextColor(if (result.startsWith("Main Server reachable (HTTP 2")) green else textDark)
                testConnectionButton.isEnabled = true
                saveSettingsButton.isEnabled = true
                connectionTestRunning = false
            }
        }
    }

    private fun updateOrganizationSummary() {
        if (!::organizationInfoText.isInitialized) return
        val verified = SecureConfig.isOrganizationVerified(this)
        val name = SecureConfig.getOrganizationName(this).ifBlank { "Not verified" }
        val code = SecureConfig.getOrganizationCode(this).ifBlank { "—" }
        val mobile = SecureConfig.getRegisteredMobile(this).ifBlank { "—" }
        organizationInfoText.text = buildString {
            append("Organization Verification: ")
            append(if (verified) "VERIFIED" else "NOT VERIFIED")
            append("\nOrganization Name: ").append(name)
            append("\nOrganization Code: ").append(code)
            append("\nRegistered Mobile: ").append(maskMobile(mobile))
        }
    }

    private fun maskMobile(value: String): String {
        if (value.length < 5) return value
        return "•".repeat((value.length - 4).coerceAtLeast(0)) + value.takeLast(4)
    }

    private fun setStatus(message: String, success: Boolean?) {
        connectionStatusText.text = message
        connectionStatusText.setTextColor(
            when (success) {
                true -> green
                false -> Color.rgb(185, 28, 28)
                null -> blue
            }
        )
    }

    private fun isValidHttpsUrl(value: String): Boolean {
        return try {
            val uri = URI(value)
            uri.scheme.equals("https", true) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (::connectionStatusText.isInitialized) updateOrganizationSummary()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Network work has strict timeouts. No API credential is logged or
        // included in a diagnostic/toast message.
    }
}
