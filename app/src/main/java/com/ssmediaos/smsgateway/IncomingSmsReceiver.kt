```kotlin
package com.ssmediaos.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class IncomingSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val messages = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not read incoming SMS", e)
            return
        }

        if (messages.isEmpty()) {
            return
        }

        val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
        val messageBody = messages.joinToString(separator = "") {
            it.messageBody.orEmpty()
        }

        Log.i(TAG, "Incoming SMS received from: $sender")

        val receivedIntent = Intent(ACTION_INCOMING_SMS).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SENDER, sender)
            putExtra(EXTRA_MESSAGE, messageBody)
            putExtra(EXTRA_TIMESTAMP, messages.firstOrNull()?.timestampMillis ?: 0L)
        }

        context.sendBroadcast(receivedIntent)
    }

    companion object {
        private const val TAG = "IncomingSmsReceiver"

        const val ACTION_INCOMING_SMS =
            "com.ssmediaos.smsgateway.ACTION_INCOMING_SMS"

        const val EXTRA_SENDER = "incoming_sms_sender"
        const val EXTRA_MESSAGE = "incoming_sms_message"
        const val EXTRA_TIMESTAMP = "incoming_sms_timestamp"
    }
}
```
