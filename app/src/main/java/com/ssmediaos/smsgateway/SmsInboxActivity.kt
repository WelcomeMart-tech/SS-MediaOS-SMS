
package com.ssmediaos.smsgateway

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsInboxActivity : Activity() {

    private lateinit var messageContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
        loadMessages()
    }

    private fun createInterface() {
        val padding = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        val title = TextView(this).apply {
            text = "Incoming SMS Inbox"
            textSize = 24f
            setTextColor(Color.rgb(24, 48, 85))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        val refreshButton = Button(this).apply {
            text = "Refresh Messages"
            setOnClickListener { loadMessages() }
        }

        val clearButton = Button(this).apply {
            text = "Clear Inbox"
            setOnClickListener {
                IncomingSmsStore.clear(this@SmsInboxActivity)
                loadMessages()
            }
        }

        messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                messageContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title, matchWidth())
        root.addView(refreshButton, matchWidth())
        root.addView(clearButton, matchWidth())
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

    private fun loadMessages() {
        messageContainer.removeAllViews()

        val messages = IncomingSmsStore.getAll(this)

        if (messages.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No incoming SMS found."
                textSize = 16f
                setTextColor(Color.DKGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }

            messageContainer.addView(emptyText, matchWidth())
            return
        }

        messages.forEach { item ->
            val sender = item.optString("sender", "Unknown sender")
            val body = item.optString("message", "")
            val timestamp = item.optLong("timestamp", 0L)

            val dateText = if (timestamp > 0L) {
                SimpleDateFormat(
                    "dd MMM yyyy, hh:mm a",
                    Locale.getDefault()
                ).format(Date(timestamp))
            } else {
                "Unknown time"
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(Color.WHITE)
            }

            val senderText = TextView(this).apply {
                text = "From: $sender"
                textSize = 17f
                setTextColor(Color.rgb(24, 48, 85))
            }

            val messageText = TextView(this).apply {
                text = body
                textSize = 15f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(8), 0, dp(8))
            }

            val dateView = TextView(this).apply {
                text = dateText
                textSize = 12f
                setTextColor(Color.GRAY)
            }

            card.addView(senderText, matchWidth())
            card.addView(messageText, matchWidth())
            card.addView(dateView, matchWidth())

            val cardParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }

            messageContainer.addView(card, cardParams)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
