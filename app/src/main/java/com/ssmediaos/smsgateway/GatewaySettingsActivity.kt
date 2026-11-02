package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Gateway settings with server-authoritative organization verification
 * and explicit selection of the SIM used by the SMS gateway.
 *
 * SIM selection is stored by Android subscription ID. No default-SIM
 * fallback is performed: the user must select an active SIM explicitly.
 */
class GatewaySettingsActivity : Activity() {

    companion object {
        private const val REQUEST_READ_PHONE_STATE = 7104
    }

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
    private lateinit var selectSimButton: Button

    @Volatile
    private var requestRunning = false

    private val blue = Color.rgb(37, 99, 235)
    private val green = Color.rgb(22, 163, 74)
    private val red = Color.rgb(185, 28, 28)
    private val amber = Color.rgb(154, 91, 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gateway_settings)
        bindViews()
        configureViews()
        addSimSelectionButton()
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

        apiTokenInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        apiTokenInput.transformationMethod =
            PasswordTransformationMethod.getInstance()

        organizationCodeInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        registeredMobileInput.inputType = InputType.TYPE_CLASS_PHONE
    }

    /**
     * Adds the SIM selector to the existing layout, immediately after
     * simStatusText. This keeps the current XML and its other controls intact.
     */
    private fun addSimSelectionButton() {
        val parent = simStatusText.parent as? ViewGroup
        if (parent == null) {
            simStatusText.text =
                "SIM selector unavailable: simStatusText must be inside a ViewGroup."
            return
        }

        selectSimButton = Button(this).apply {
            text = "Select SIM Card"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(37, 99, 235))
            )
            setOnClickListener { requestSimPermissionThenShowList() }
        }

        val index = parent.indexOfChild(simStatusText) + 1
        val params = when (parent) {
            is LinearLayout -> LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        parent.addView(selectSimButton, index.coerceAtLeast(0), params)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun loadSettings() {
        deviceIdInput.setText(SecureConfig.getDeviceCode(this))
        apiTokenInput.setText(SecureConfig.getCompanyApiKey(this).orEmpty())
        organizationCodeInput.setText(SecureConfig.getOrganizationCode(this))
        registeredMobileInput.setText(SecureConfig.getRegisteredMobile(this))

        refreshOrganizationSummary()
        refreshActivationStatus()
        refreshSimStatus()
        setConnectionStatus(
            "Enter the Company API Key, Organization Code and registered mobile number.",
            null
        )
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
        setConnectionStatus(
            "Settings saved securely. Organization is not verified until Main Server confirms it.",
            true
        )
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
        setConnectionStatus(
            "Main Server is checking Company API Key, Client API, Client Database and organization details…",
            null
        )

        ApiClient.diagnoseGateway(apiKey, code, mobile) { result ->
            runOnUiThread {
                setBusy(false)
                result.fold(
                    onSuccess = {
                        refreshOrganizationSummary()
                        refreshActivationStatus()
                        setConnectionStatus(
                            "Organization and registered mobile verified successfully by Main Server.",
                            true
                        )
                        Toast.makeText(
                            this,
                            "Organization verified",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { error ->
                        refreshOrganizationSummary()
                        refreshActivationStatus()
                        setConnectionStatus(
                            error.message ?: "Organization verification failed.",
                            false
                        )
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
                        setConnectionStatus(
                            "Device activated. Select an active SIM before starting the gateway.",
                            true
                        )
                        Toast.makeText(
                            this,
                            "Device activated successfully",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    onFailure = { error ->
                        refreshActivationStatus()
                        setConnectionStatus(
                            error.message ?: "Device activation failed.",
                            false
                        )
                    }
                )
            }
        }
    }

    private fun requestSimPermissionThenShowList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                REQUEST_READ_PHONE_STATE
            )
            return
        }
        showAvailableSims()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_READ_PHONE_STATE) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                showAvailableSims()
            } else {
                simStatusText.text =
                    "SIM status: Phone permission denied. Allow Phone permission to list SIM cards."
                simStatusText.setTextColor(red)
                Toast.makeText(
                    this,
                    "READ_PHONE_STATE permission is required to select a SIM.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("MissingPermission")
    private fun showAvailableSims() {
        val manager = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager

        if (manager == null) {
            setSimMessage("SIM status: SIM manager is unavailable on this device.", false)
            return
        }

        val activeSims = try {
            manager.activeSubscriptionInfoList.orEmpty()
        } catch (security: SecurityException) {
            setSimMessage("SIM status: Phone permission is required to read SIM cards.", false)
            return
        } catch (error: Exception) {
            setSimMessage(
                "SIM status: Could not read SIM cards (${error.localizedMessage ?: "unknown error"}).",
                false
            )
            return
        }

        if (activeSims.isEmpty()) {
            SecureConfig.clearSelectedSimSubscriptionId(this)
            refreshSimStatus()
            setSimMessage(
                "No active SIM found. Insert/enable a SIM card and try again.",
                false
            )
            return
        }

        val labels = activeSims.mapIndexed { index, info ->
            buildSimLabel(info, index)
        }.toTypedArray()

        val selectedId = SecureConfig.getSelectedSimSubscriptionId(this)
        val checkedIndex = activeSims.indexOfFirst { it.subscriptionId == selectedId }

        AlertDialog.Builder(this)
            .setTitle("Select SIM for SMS Gateway")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = activeSims.getOrNull(which)
                if (selected == null) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }

                val saved = SecureConfig.saveSelectedSimSubscriptionId(
                    this,
                    selected.subscriptionId
                )
                if (saved) {
                    refreshSimStatus()
                    Toast.makeText(
                        this,
                        "Selected ${buildSimLabel(selected, which)}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    setSimMessage("Could not save selected SIM. Please try again.", false)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear selection") { _, _ ->
                SecureConfig.clearSelectedSimSubscriptionId(this)
                refreshSimStatus()
            }
            .show()
    }

    private fun buildSimLabel(info: SubscriptionInfo, fallbackIndex: Int): String {
        val slot = if (info.simSlotIndex >= 0) info.simSlotIndex + 1 else fallbackIndex + 1
        val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: "Unknown carrier"
        val number = info.number?.takeIf { it.isNotBlank() }
            ?.let { " • ${maskMobile(it)}" }
            .orEmpty()
        return "SIM $slot • $carrier$number"
    }

    @Suppress("MissingPermission")
    private fun refreshSimStatus() {
        val selectedId = SecureConfig.getSelectedSimSubscriptionId(this)
        if (selectedId == null) {
            simStatusText.text =
                "SIM status: No SIM selected. Tap Select SIM Card and choose the SIM assigned for gateway use."
            simStatusText.setTextColor(amber)
            return
        }

        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            simStatusText.text =
                "SIM status: A SIM selection is saved. Grant Phone permission to verify that it is still active."
            simStatusText.setTextColor(amber)
            return
        }

        val activeSims = try {
            (getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
                .activeSubscriptionInfoList.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        val selected = activeSims.firstOrNull { it.subscriptionId == selectedId }
        if (selected == null) {
            SecureConfig.clearSelectedSimSubscriptionId(this)
            simStatusText.text =
                "SIM status: Previously selected SIM is no longer active. Please select a SIM again."
            simStatusText.setTextColor(amber)
            return
        }

        val slot = if (selected.simSlotIndex >= 0) selected.simSlotIndex + 1 else 1
        val carrier = selected.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: "Unknown carrier"
        simStatusText.text = "SIM status: Selected SIM $slot • $carrier"
        simStatusText.setTextColor(green)
    }

    private fun setSimMessage(message: String, success: Boolean) {
        simStatusText.text = message
        simStatusText.setTextColor(if (success) green else red)
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
        organizationMobileText.text =
            "Registered Mobile Number: ${maskMobile(mobile)}"
        organizationStatusText.text =
            "Organization Status: ${if (verified) status.uppercase() else "NOT VERIFIED"}"
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
        if (digits.length < 5 || value == "Not verified") return value
        return "•".repeat((digits.length - 4).coerceAtLeast(0)) + digits.takeLast(4)
    }

    private fun setBusy(busy: Boolean) {
        requestRunning = busy
        verifyMobileButton.isEnabled = !busy
        testConnectionButton.isEnabled = !busy
        saveSettingsButton.isEnabled = !busy
        activateDeviceButton.isEnabled = !busy
        selectSimButton.isEnabled = !busy
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
            refreshSimStatus()
        }
    }
}
