package com.ssmediaos.smsgateway

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.view.ViewGroup
import android.util.TypedValue

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var permissionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.rgb(24, 48, 85))
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "SMS Gateway Dashboard"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.rgb(40, 60, 80))
            setPadding(0, dp(24), 0, dp(24))
        }

        permissionButton = Button(this).apply {
            text = "Check SMS Permissions"
            setOnClickListener {
                requestSmsPermissions()
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
        content.addView(permissionButton, matchWidth())
        content.addView(refreshButton, matchWidth())

        val scrollView = ScrollView(this).apply {
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scrollView)
    }

    private fun updateStatus() {
        val smsGranted =
            checkSelfPermission(Manifest.permission.SEND_SMS) ==
                PackageManager.PERMISSION_GRANTED

        statusText.text = buildString {
            appendLine("App Status: Running")
            appendLine()
            appendLine(
                if (smsGranted) {
                    "SMS Permission: Granted"
                } else {
                    "SMS Permission: Not granted"
                }
            )
            appendLine()
            appendLine("Gateway Status: Not configured")
            appendLine()
            append("Note: Server configuration is required to connect to a gateway.")
        }

        permissionButton.text =
            if (smsGranted) "SMS Permission Granted" else "Grant SMS Permission"
    }

    private fun requestSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )

            requestPermissions(permissions, SMS_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST) {
            updateStatus()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val SMS_PERMISSION_REQUEST = 1001
    }
}
