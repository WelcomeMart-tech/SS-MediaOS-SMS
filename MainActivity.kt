package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var permissionText: TextView
    private lateinit var gatewayText: TextView
    private lateinit var content: LinearLayout

    private var gatewayStartRequested = false

    private val navy = Color.rgb(16, 27, 53)
    private val blue = Color.rgb(24, 119, 242)
    private val green = Color.rgb(34, 197, 94)
    private val backgroundColor = Color.rgb(244, 247, 252)
    private val textDark = Color.rgb(30, 41, 59)
    private val textMuted = Color.rgb(100, 116, 139)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = navy
        window.navigationBarColor = navy
        window.decorView.systemUiVisibility = 0

        createInterface()
        requestRequiredPermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()

        if (::statusText.isInitialized) {
            updateStatus()
        }
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        // Brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(22))
            background = roundedGradient(
                intArrayOf(
                    Color.rgb(16, 27, 53),
                    Color.rgb(23, 59, 112),
                    Color.rgb(24, 119, 242)
                ),
                24
            )
            elevation = dp(4).toFloat()
        }

        val brandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = TextView(this).apply {
            text = "SMS"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            background = roundedBackground(
                Color.rgb(36, 87, 157),
                18
            )
            contentDescription = "SS MediaOS SMS Gateway logo"
        }

        brandRow.addView(
            logo,
            LinearLayout.LayoutParams(dp(66), dp(66))
        )

        val brandText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }

        brandText.addView(
            textView("SS MediaOS", 22f, white, true)
        )

        brandText.addView(
            textView(
                "SMS GATEWAY",
                12f,
                Color.rgb(207, 226, 255),
                true
            ).apply {
                letterSpacing = 0.12f
                setPadding(0, dp(5), 0, 0)
            }
        )

        brandRow.addView(
            brandText,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        header.addView(brandRow)

        header.addView(
            textView(
                "Smart messaging. Reliable connectivity.",
                13f,
                Color.rgb(230, 239, 255),
                false
            ).apply {
                setPadding(0, dp(18), 0, 0)
            }
        )

        header.addView(
            textView("Owner: SS MediaOS", 12f, white, true).apply {
                setPadding(0, dp(16), 0, 0)
            }
        )

        header.addView(
            textView(
                "Developer: Shivam Yadav",
                12f,
                Color.rgb(220, 232, 255),
                false
            ).apply {
                setPadding(0, dp(5), 0, 0)
            }
        )

        content.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // Dashboard
        content.addView(
            sectionTitle(
                "Dashboard",
                "Gateway overview and controls"
            )
        )

        // Status cards
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val appCard = createStatCard(
            "APP STATUS",
            "RUNNING",
            "Application",
            green
        )

        val connectionCard = createStatCard(
            "SERVER",
            "CHECKING",
            "Configuration status",
            blue
        )

        statusRow.addView(
            appCard,
            LinearLayout.LayoutParams(0, dp(120), 1f).apply {
                marginEnd = dp(7)
            }
        )

        statusRow.addView(
            connectionCard,
            LinearLayout.LayoutParams(0, dp(120), 1f).apply {
                marginStart = dp(7)
            }
        )

        content.addView(
            statusRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        val permissionCard = createStatCard(
            "SMS PERMISSION",
            "CHECKING",
            "Android permission",
            blue
        )

        content.addView(
            permissionCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(112)
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // Gateway controls
        content.addView(
            sectionTitle(
                "Gateway Controls",
                "Manage your SMS gateway"
            )
        )

        val gatewayCard = createCard()

        gatewayText = textView(
            "Gateway service status will appear here.",
            14f,
            textDark,
            true
        )

        gatewayText.setPadding(0, 0, 0, dp(14))
        gatewayCard.addView(gatewayText)

        gatewayCard.addView(
            actionButton(
                "▶   Start Gateway Service",
                green
            ) {
                startGatewayService()
            },
            buttonLayout()
        )

        gatewayCard.addView(
            actionButton(
                "■   Stop Gateway Service",
                Color.rgb(220, 53, 69)
            ) {
                stopGatewayService()
            },
            buttonLayout()
        )

        content.addView(
            gatewayCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // SMS services
        content.addView(
            sectionTitle(
                "SMS Services",
                "Access your SMS tools"
            )
        )

        val smsCard = createCard()

        smsCard.addView(
            textView(
                "SMS Gateway",
                17f,
                textDark,
                true
            )
        )

        smsCard.addView(
            textView(
                "Use the authorized gateway to send business messages. " +
                    "This app does not access your incoming SMS inbox.",
                13f,
                textMuted,
                false
            ).apply {
                setPadding(0, dp(6), 0, dp(12))
            }
        )

        content.addView(
            smsCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // Gateway settings
        content.addView(
            sectionTitle(
                "Gateway Settings",
                "Configure your server and device"
            )
        )

        val settingsCard = createCard()

        settingsCard.addView(
            textView(
                "Server URL, Device Code and Activation Token",
                16f,
                textDark,
                true
            )
        )

        settingsCard.addView(
            textView(
                "Configure the SS MediaOS server and activate this " +
                    "device before sending SMS.",
                13f,
                textMuted,
                false
            ).apply {
                setPadding(0, dp(7), 0, dp(14))
            }
        )

        settingsCard.addView(
            actionButton(
                "⚙   Open Gateway Settings",
                blue
            ) {
                openGatewaySettings()
            },
            buttonLayout()
        )

        content.addView(
            settingsCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // System information
        content.addView(
            sectionTitle(
                "System Information",
                "Current device status"
            )
        )

        val systemCard = createCard()

        serverText = textView(
            "Server: Not configured",
            14f,
            textDark,
            false
        )

        permissionText = textView(
            "SMS Permission: Checking",
            14f,
            textDark,
            false
        )

        statusText = textView(
            "App Status: Running",
            14f,
            textDark,
            false
        )

        systemCard.addView(serverText)

        systemCard.addView(
            permissionText.apply {
                setPadding(0, dp(12), 0, 0)
            }
        )

        systemCard.addView(
            statusText.apply {
                setPadding(0, dp(12), 0, 0)
            }
        )

        systemCard.addView(
            actionButton(
                "↻   Refresh Status",
                blue
            ) {
                updateStatus("Status refreshed.")
            }.apply {
                setPadding(dp(12), 0, dp(12), 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(18)
            }
        )

        content.addView(
            systemCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(22)
            }
        )

        // Footer
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(18), dp(10), dp(12))
        }

        footer.addView(
            textView(
                "SS MediaOS SMS Gateway",
                14f,
                navy,
                true
            )
        )

        footer.addView(
            textView(
                "Developed by Shivam Yadav",
                12f,
                textMuted,
                false
            ).apply {
                setPadding(0, dp(6), 0, 0)
            }
        )

        content.addView(footer)

        scrollView.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun createStatCard(
        label: String,
        value: String,
        description: String,
        accent: Int
    ): LinearLayout {
        val card = createCard()

        card.addView(
            textView(
                label,
                11f,
                textMuted,
                true
            )
        )

        val valueText = textView(
            value,
            20f,
            accent,
            true
        ).apply {
            tag = when (label) {
                "SMS PERMISSION" -> "value_SMS_PERMISSION"
                else -> "value_$label"
            }
            setPadding(0, dp(10), 0, dp(4))
        }

        card.addView(valueText)

        card.addView(
            textView(
                description,
                12f,
                textMuted,
                false
            )
        )

        return card
    }

    private fun sectionTitle(
        title: String,
        subtitle: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), 0, 0, dp(12))

            addView(
                textView(
                    title,
                    20f,
                    navy,
                    true
                )
            )

            addView(
                textView(
                    subtitle,
                    12f,
                    textMuted,
                    false
                ).apply {
                    setPadding(0, dp(4), 0, 0)
                }
            )
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(white, 18)
            elevation = dp(2).toFloat()
        }
    }

    private fun textView(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }

            gravity = Gravity.CENTER_VERTICAL
        }
    }

    private fun actionButton(
        label: String,
        color: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            setTextColor(white)
            isAllCaps = false
            background = roundedBackground(color, 12)
            setPadding(dp(12), dp(6), dp(12), dp(6))

            setOnClickListener {
                onClick()
            }
        }
    }

    private fun buttonLayout(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ).apply {
            bottomMargin = dp(10)
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun roundedGradient(
        colors: IntArray,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            colors
        ).apply {
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.SEND_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun startGatewayService() {
        val smsGranted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        if (!smsGranted) {
            updateStatus("Please grant SMS permission first.")
            requestRequiredPermissions()
            return
        }

        val serverUrl = SecureConfig.getServerUrl(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val activationToken = SecureConfig.getActivationToken(this)
        val deviceToken = SecureConfig.getDeviceToken(this)

        if (
            serverUrl.isNullOrBlank() ||
            deviceCode.isNullOrBlank() ||
            activationToken.isNullOrBlank()
        ) {
            updateStatus(
                "Complete Gateway Settings before starting the gateway."
            )
            showToast(
                "Please save the server URL, Device Code and " +
                    "Activation Token first."
            )
            return
        }

        if (deviceToken.isNullOrBlank()) {
            updateStatus(
                "This device is not activated yet. " +
                    "Activate it in Gateway Settings."
            )
            showToast(
                "Activate this device before starting the gateway."
            )
            return
        }

        try {
            val intent = Intent(
                this,
                GatewayService::class.java
            ).apply {
                action = GatewayService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            gatewayStartRequested = true
            updateStatus("Gateway service start requested.")
        } catch (e: Exception) {
            gatewayStartRequested = false
            updateStatus(
                "Could not start service: ${e.localizedMessage}"
            )
        }
    }

    private fun stopGatewayService() {
        val stopped = stopService(
            Intent(this, GatewayService::class.java)
        )

        gatewayStartRequested = false

        updateStatus(
            if (stopped) {
                "Gateway service stop requested."
            } else {
                "Stop requested. Service may already be stopped."
            }
        )
    }

    private fun openGatewaySettings() {
        try {
            startActivity(
                Intent(
                    this,
                    GatewaySettingsActivity::class.java
                )
            )
        } catch (e: Exception) {
            showToast(
                "Could not open Gateway Settings. " +
                    "Check AndroidManifest.xml. " +
                    (e.localizedMessage ?: "")
            )
        }
    }

    private fun updateStatus(message: String? = null) {
        val smsGranted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        val serverUrl = SecureConfig.getServerUrl(this)
        val deviceCode = SecureConfig.getDeviceCode(this)
        val activationToken = SecureConfig.getActivationToken(this)
        val deviceToken = SecureConfig.getDeviceToken(this)

        serverText.text =
            "Server: ${serverUrl ?: "Not configured"}"

        permissionText.text = if (smsGranted) {
            "SMS Permission: Granted"
        } else {
            "SMS Permission: Not granted"
        }

        statusText.text = buildString {
            appendLine("App Status: Running")

            appendLine(
                if (smsGranted) {
                    "SMS sending permission is granted."
                } else {
                    "SMS sending permission is required."
                }
            )

            if (message != null) {
                appendLine()
                append(message)
            }
        }

        val serverValue = content.findViewWithTag<TextView>(
            "value_SERVER"
        )

        serverValue?.text = when {
            serverUrl.isNullOrBlank() -> "NOT SET"
            deviceToken.isNullOrBlank() -> "NOT ACTIVATED"
            else -> "ACTIVATED"
        }

        val permissionValue = content.findViewWithTag<TextView>(
            "value_SMS_PERMISSION"
        )

        permissionValue?.text = if (smsGranted) {
            "GRANTED"
        } else {
            "NOT GRANTED"
        }

        gatewayText.text = buildString {
            appendLine(
                if (gatewayStartRequested) {
                    "Gateway start has been requested."
                } else {
                    "Gateway service controls"
                }
            )

            appendLine(
                if (smsGranted) {
                    "SMS permission is available."
                } else {
                    "Grant SMS permission before starting the gateway."
                }
            )

            appendLine(
                if (serverUrl.isNullOrBlank()) {
                    "Server URL is not configured."
                } else {
                    "Server URL is configured."
                }
            )

            append(
                when {
                    deviceCode.isNullOrBlank() ||
                        activationToken.isNullOrBlank() ->
                        "Device Code and Activation Token are not configured."

                    deviceToken.isNullOrBlank() ->
                        "Device is not activated. Activate it in Gateway Settings."

                    else ->
                        "Device activation token is available."
                }
            )
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
