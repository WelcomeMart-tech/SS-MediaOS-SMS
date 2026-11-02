package com.ssmediaos.smsgateway

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * Gateway settings and server-authoritative organization verification.
 * Uses the existing activity_gateway_settings.xml IDs; no layout or manifest
 * changes are required when those IDs match the supplied project layout.
 */
class GatewaySettingsActivity : Activity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var apiTokenInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var organizationCodeInput: EditText
    private lateinit var registeredMobileInput: EditText

    private lateinit var verifyMobileButton: Button
    private lateinit var saveSettingsButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var activateDeviceButton: Button

    private lateinit var connectionStatusText: TextView
    private lateinit var activationStatusText: TextView
    private lateinit var organizationNameText: TextView
    private lateinit var organizationIdText: TextView
    private lateinit var organizationCodeText: TextView
    private lateinit var organizationMobileText: TextView
    private lateinit var organizationStatusText: TextView
    private lateinit var simStatusText: TextView

    @Volatile private var requestRunning = false

    private val blue = Color.rgb(37, 99, 235)
    private val green = Color.rgb(22, 163, 74)
    private val red = Color.rgb(185, 28, 28)
    private val amber = Color.rgb(154, 91, 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)
        bindViews()
        configureViews()
        loadSettings()
        bindActions()
    }

    private fun bindViews() {
        serverUrlInput = findViewById(R.id.serverUrlInput)
        apiTokenInput = findViewById(R.id.apiTokenInput)
        deviceIdInput = findViewById(R.id.deviceIdInput)
        organizationCodeInput = findViewById(R.id.organizationCodeInput)
        registeredMobileInput = findViewById(R.id.registeredMobileInput)
        verifyMobileButton = findViewById(R.id.verifyMobileButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        activateDeviceButton = findViewById(R.id.activateDeviceButton)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        activationStatusText = findViewById(R.id.activationStatusText)
        organizationNameText = findViewById(R.id.organizationNameText)
        organizationIdText = findViewById(R.id.organizationIdText)
        organizationCodeText = findViewById(R.id.organizationCodeText)
        organizationMobileText = findViewById(R.id.organizationMobileText)
        organizationStatusText = findViewById(R.id.organizationStatusText)
        simStatusText = findViewById(R.id.simStatusText)
    }

    private fun configureViews() {
        serverUrlInput.setText(SecureConfig.DEFAULT_SERVER_URL)
        serverUrlInput.isEnabled = false
        deviceIdInput.isEnabled = false
        deviceIdInput.isFocusable = false
        apiTokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        apiTokenInput.transformationMethod = PasswordTransformationMethod.getInstance()
        organizationCodeInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        registeredMobileInput.inputType = InputType.TYPE_CLASS_PHONE
    }

    private fun loadSettings() {
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        organizationCodeInput.setText(SecureConfig.getOrganizationCode(this))
        registeredMobileInput.setText(SecureConfig.getRegisteredMobile(this))
        refreshOrganizationSummary()
        refreshActivationStatus()
        simStatusText.text = if (SecureConfig.getSelectedSimSubscriptionId(this) != null) {
            "SIM status: A SIM is selected. The app will use only this SIM."
        } else {
            "SIM status: No SIM selected. Select an assigned SIM before starting the gateway."
        }
        setConnectionStatus("Enter the Company API Key, Organization Code and registered mobile number.", null)
    }

    private fun bindActions() {
        saveSettingsButton.setOnClickListener { saveCredentialsAndIdentity() }
        verifyMobileButton.setOnClickListener { verifyOrganization() }
        testConnectionButton.setOnClickListener { verifyOrganization() }
        activateDeviceButton.setOnClickListener { activateDevice() }
    }

    private fun saveCredentialsAndIdentity(): Boolean {
        val apiKey = apiTokenInput.text?.toString()?.trim().orEmpty()
        val code = organizationCodeInput.text?.toString()?.trim()?.uppercase().orEmpty()
        val mobile = registeredMobileInput.text?.toString()?.trim().orEmpty()

        if (apiKey.length < 16) {
            apiTokenInput.error = "Enter a valid Company API Key"
            apiTokenInput.requestFocus()
            return false
        }
        if (code.isBlank()) {
            organizationCodeInput.error = "Enter the Organization Code"
            organizationCodeInput.requestFocus()
            return false
        }
        val digits = mobile.filter { it.isDigit() }
        if (digits.length !in 8..15) {
            registeredMobileInput.error = "Enter the registered mobile number"
            registeredMobileInput.requestFocus()
            return false
        }

        val deviceCode = SecureConfig.getDeviceCode(this)
        val credentialsSaved = SecureConfig.saveActivationSettings(
            context = this,
            serverUrl = SecureConfig.DEFAULT_SERVER_URL,
            deviceCode = deviceCode,
            activationToken = apiKey
        )
        if (!credentialsSaved) {
            setConnectionStatus("Could not securely save Company API Key.", false)
            return false
        }

        if (!SecureConfig.saveOrganizationIdentity(this, code, digits)) {
            setConnectionStatus("Could not save Organization Code and mobile number.", false)
            return false
        }

        deviceIdInput.setText(deviceCode)
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        refreshOrganizationSummary()
        setConnectionStatus("Settings saved securely. Organization is not verified until Main Server confirms it.", true)
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun verifyOrganization() {
        if (requestRunning) return
        if (!saveCredentialsAndIdentity()) return

        val apiKey = SecureConfig.getCompanyApiKey(this).orEmpty()
        val code = organizationCodeInput.text?.toString()?.trim().orEmpty()
        val mobile = registeredMobileInput.text?.toString()?.trim().orEmpty()

        setBusy(true)
        setConnectionStatus("Main Server is checking Company API Key, Client API, Client Database and organization details…", null)

        ApiClient.diagnoseGateway(apiKey, code, mobile) { result ->
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = {
                        refreshOrganizationSummary()
                        refreshActivationStatus()
                        setConnectionStatus("Organization and registered mobile verified successfully by Main Server.", true)
                        Toast.makeText(this, "Organization verified", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        refreshOrganizationSummary()
                        refreshActivationStatus()
                        setConnectionStatus(error.message ?: "Organization verification failed.", false)
                    }
                )
            }
        }
    }

    private fun activateDevice() {
        if (requestRunning) return
        if (!SecureConfig.isOrganizationVerified(this)) {
            setConnectionStatus("Verify Organization and registered mobile first.", false)
            return
        }
        if (SecureConfig.getCompanyApiKey(this).isNullOrBlank()) {
            setConnectionStatus("Company API Key is missing. Save settings first.", false)
            return
        }

        setBusy(true)
        activationStatusText.text = "Device status: ACTIVATION IN PROGRESS"
        activationStatusText.setTextColor(blue)
        ApiClient.activate { result ->
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = {
                        refreshActivationStatus()
                        setConnectionStatus("Device activated. You can return to Dashboard and start the gateway after selecting a SIM.", true)
                        Toast.makeText(this, "Device activated successfully", Toast.LENGTH_LONG).show()
                    },
                    onFailure = { error ->
                        refreshActivationStatus()
                        setConnectionStatus(error.message ?: "Device activation failed.", false)
                    }
                )
            }
        }
    }

    private fun refreshOrganizationSummary() {
        val verified = SecureConfig.isOrganizationVerified(this)
        val name = SecureConfig.getOrganizationName(this).ifBlank { "Not verified" }
        val id = SecureConfig.getOrganizationId(this).ifBlank { "Not verified" }
        val code = SecureConfig.getOrganizationCode(this).ifBlank { "Not verified" }
        val mobile = SecureConfig.getRegisteredMobile(this).ifBlank { "Not verified" }
        val status = SecureConfig.getOrganizationStatus(this).ifBlank { "PENDING" }

        organizationNameText.text = "Organization Name: $name"
        organizationIdText.text = "Organization ID: $id"
        organizationCodeText.text = "Organization Code: $code"
        organizationMobileText.text = "Registered Mobile Number: ${maskMobile(mobile)}"
        organizationStatusText.text = "Organization Status: ${if (verified) status.uppercase() else "NOT VERIFIED"}"
        organizationStatusText.setTextColor(if (verified) green else amber)
    }

    private fun refreshActivationStatus() {
        val active = !SecureConfig.getDeviceToken(this).isNullOrBlank()
        activationStatusText.text = if (active) {
            "Device status: ACTIVATED"
        } else {
            "Device status: NOT ACTIVATED"
        }
        activationStatusText.setTextColor(if (active) green else amber)
    }

    private fun maskMobile(value: String): String {
        val digits = value.filter { it.isDigit() }
        if (digits.length < 5 || digits == "Not verified") return value
        return "•".repeat((digits.length - 4).coerceAtLeast(0)) + digits.takeLast(4)
    }

    private fun setBusy(busy: Boolean) {
        requestRunning = busy
        verifyMobileButton.isEnabled = !busy
        testConnectionButton.isEnabled = !busy
        saveSettingsButton.isEnabled = !busy
        activateDeviceButton.isEnabled = !busy
    }

    private fun setConnectionStatus(message: String, success: Boolean?) {
        connectionStatusText.text = message
        connectionStatusText.setTextColor(
            when (success) {
                true -> green
                false -> red
                null -> blue
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::organizationNameText.isInitialized) {
            refreshOrganizationSummary()
            refreshActivationStatus()
        }
    }
}
