package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        requestRequiredPermissions()
        updateStatus()
    }

    private fun createInterface() {
        val padding = dp(20)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        val title = TextView(this).apply {
            text = "SS MediaOS SMS Gateway"
            textSize = 24f
            setTextColor(Color.rgb(24, 48, 85))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "SMS Gateway Dashboard"
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(40, 60, 80))
            setPadding(0, dp(24), 0, dp(24))
        }

        val startButton = Button(this).apply {
            text = "Start Gateway Service"
            setOnClickListener {
                startGatewayService()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Gateway Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, GatewayService::class.java))
                updateStatus("Gateway service stopped.")
            }
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Status"
            setOnClickListener {
                updateStatus()
            }
        }

        content.addView(title, matchWidth())
        content.addView(subtitle, matchWidth())
        content.addView(statusText, matchWidth())
        content.addView(startButton, matchWidth())
        content.addView(stopButton, matchWidth())
        content.addView(refreshButton, matchWidth())

        setContentView(ScrollView(this).apply {
            addView(content)
        })
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = required.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST_CODE)
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

        val intent = Intent(this, GatewayService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            updateStatus("Gateway service start requested.")
        } catch (exception: Exception) {
            updateStatus("Could not start service: ${exception.message}")
        }
    }

    private fun updateStatus(message: String? = null) {
        val smsGranted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        statusText.text = buildString {
            appendLine("App Status: Running")
            appendLine()
            appendLine(
                if (smsGranted) "SMS Permission: Granted"
                else "SMS Permission: Not granted"
            )
            appendLine()
            appendLine("Server: ${SecureConfig.getServerUrl(this@MainActivity) ?: "Not configured"}")
            appendLine()
            appendLine("Gateway service: Check Android notification/status")
            if (message != null) {
                appendLine()
                append(message)
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
