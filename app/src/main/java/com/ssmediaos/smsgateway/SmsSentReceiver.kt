package com.ssmediaos.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val result = when (resultCode) {
            Activity.RESULT_OK -> "SMS_SENT"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
            else -> "UNKNOWN_ERROR_$resultCode"
        }

        Log.i(TAG, "SMS send result: $result")

        // The gateway can later forward this result to its configured server.
        // Do not log phone numbers or message contents here.
        val resultIntent = Intent(ACTION_SMS_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_RESULT, result)
        }

        context.sendBroadcast(resultIntent)
    }

    companion object {
        private const val TAG = "SmsSentReceiver"

        const val ACTION_SMS_RESULT =
            "com.ssmediaos.smsgateway.ACTION_SMS_RESULT"

        const val EXTRA_RESULT = "sms_result"
    }
}
