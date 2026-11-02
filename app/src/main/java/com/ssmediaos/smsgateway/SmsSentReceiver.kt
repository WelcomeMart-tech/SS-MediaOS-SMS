package com.ssmediaos.smsgateway

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log

/**
 * Receives ONLY SmsManager sent PendingIntent callbacks.
 *
 * This receiver does not listen for incoming SMS, does not read the inbox,
 * and does not use the SMS_RECEIVED action. It reports Android's send
 * submission result for each SMS part to the running GatewayService.
 * A successful sent callback is not a delivery receipt.
 */
class SmsSentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_SMS_RESULT) {
            return
        }

        // This receiver is intended only for the explicit PendingIntent
        // created by SmsHelper. Reject callbacks missing our correlation data.
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1L)
        val requestId = intent.getStringExtra(EXTRA_GATEWAY_REQUEST_ID)
            ?.trim()
            .orEmpty()
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        val totalParts = intent.getIntExtra(EXTRA_TOTAL_PARTS, -1)
        val requestCode = intent.getIntExtra(EXTRA_REQUEST_CODE, -1)

        if (
            jobId <= 0L ||
            !REQUEST_ID_PATTERN.matches(requestId) ||
            requestCode < 0 ||
            totalParts !in 1..MAX_PARTS ||
            partIndex !in 0 until totalParts
        ) {
            Log.w(TAG, "Rejected invalid sent-SMS callback")
            return
        }

        val androidResultCode = resultCode
        val sent = androidResultCode == Activity.RESULT_OK
        val resultLabel = if (sent) RESULT_SENT else mapFailure(androidResultCode)

        // Keep this internal event private to this application. GatewayService
        // consumes ACTION_SMS_PART_RESULT using a non-exported dynamic receiver.
        val serviceEvent = Intent(ACTION_SMS_PART_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_GATEWAY_REQUEST_ID, requestId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
            putExtra(EXTRA_SUCCESS, sent)
            putExtra(EXTRA_RESULT, resultLabel)
            putExtra(EXTRA_REQUEST_CODE, requestCode)
        }

        try {
            context.sendBroadcast(serviceEvent)
            Log.i(
                TAG,
                "Sent-SMS callback forwarded: job=$jobId, " +
                    "part=${partIndex + 1}/$totalParts, success=$sent"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to forward sent-SMS callback for job=$jobId", e)
        }
    }

    private fun mapFailure(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS_RADIO_OFF"
        SmsManager.RESULT_ERROR_NULL_PDU -> "SMS_NULL_PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS_NO_SERVICE"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "SMS_LIMIT_EXCEEDED"
        else -> "SMS_SEND_FAILED_$code"
    }

    companion object {
        private const val TAG = "SmsSentReceiver"
        private const val MAX_PARTS = 255
        private val REQUEST_ID_PATTERN = Regex("^[a-f0-9]{32}$")

        // Must match SmsHelper's sent PendingIntent action.
        const val ACTION_SMS_RESULT =
            "com.ssmediaos.smsgateway.action.SMS_RESULT"

        // Private in-app event consumed by GatewayService.
        const val ACTION_SMS_PART_RESULT =
            "com.ssmediaos.smsgateway.action.SMS_PART_RESULT"

        const val EXTRA_REQUEST_CODE = "sms_request_code"
        const val EXTRA_JOB_ID = "sms_job_id"
        const val EXTRA_GATEWAY_REQUEST_ID = "gateway_request_id"
        const val EXTRA_PART_INDEX = "sms_part_index"
        const val EXTRA_TOTAL_PARTS = "sms_total_parts"
        const val EXTRA_SUCCESS = "sms_success"
        const val EXTRA_RESULT = "sms_result"

        private const val RESULT_SENT = "SMS_SENT"
    }
}
