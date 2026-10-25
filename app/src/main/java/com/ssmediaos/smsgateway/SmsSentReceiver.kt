
package com.ssmediaos.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMS_RESULT) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        val requestCode = intent.getIntExtra(
            EXTRA_REQUEST_CODE,
            -1
        )

        val result = when (resultCode) {
            Activity.RESULT_OK -> STATUS_SMS_SENT

            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                STATUS_GENERIC_FAILURE

            SmsManager.RESULT_ERROR_NO_SERVICE ->
                STATUS_NO_SERVICE

            SmsManager.RESULT_ERROR_NULL_PDU ->
                STATUS_NULL_PDU

            SmsManager.RESULT_ERROR_RADIO_OFF ->
                STATUS_RADIO_OFF

            else -> "UNKNOWN_ERROR_$resultCode"
        }

        Log.i(
            TAG,
            "SMS submission result: $result, requestCode=$requestCode"
        )

        val resultIntent = Intent(ACTION_SMS_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_REQUEST_CODE, requestCode)
        }

        context.sendBroadcast(resultIntent)
    }

    companion object {
        private const val TAG = "SmsSentReceiver"

        const val ACTION_SMS_RESULT =
            "com.ssmediaos.smsgateway.ACTION_SMS_RESULT"

        const val EXTRA_RESULT = "sms_result"

        const val EXTRA_REQUEST_CODE = "request_code"

        const val STATUS_SMS_SENT = "SMS_SENT"
        const val STATUS_GENERIC_FAILURE = "GENERIC_FAILURE"
        const val STATUS_NO_SERVICE = "NO_SERVICE"
        const val STATUS_NULL_PDU = "NULL_PDU"
        const val STATUS_RADIO_OFF = "RADIO_OFF"
    }
}
