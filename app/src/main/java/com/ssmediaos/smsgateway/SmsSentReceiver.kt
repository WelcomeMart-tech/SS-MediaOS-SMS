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

        val jobId = intent.getLongExtra(
            EXTRA_JOB_ID,
            -1L
        )

        val requestId = intent.getStringExtra(
            EXTRA_GATEWAY_REQUEST_ID
        ).orEmpty()

        val partIndex = intent.getIntExtra(
            EXTRA_PART_INDEX,
            -1
        )

        val totalParts = intent.getIntExtra(
            EXTRA_TOTAL_PARTS,
            1
        )

        // Reject callbacks without valid job and part information.
        if (
            requestCode < 0 ||
            jobId <= 0L ||
            !requestId.matches(Regex("^[a-f0-9]{32}$")) ||
            totalParts < 1 ||
            partIndex !in 0 until totalParts
        ) {
            Log.e(
                TAG,
                "Invalid SMS callback metadata; callback ignored. " +
                    "jobId=$jobId, requestCode=$requestCode, " +
                    "partIndex=$partIndex, totalParts=$totalParts"
            )
            return
        }

        val result = when (resultCode) {
            Activity.RESULT_OK ->
                STATUS_SMS_SENT

            SmsManager.RESULT_ERROR_GENERIC_FAILURE ->
                STATUS_GENERIC_FAILURE

            SmsManager.RESULT_ERROR_NO_SERVICE ->
                STATUS_NO_SERVICE

            SmsManager.RESULT_ERROR_NULL_PDU ->
                STATUS_NULL_PDU

            SmsManager.RESULT_ERROR_RADIO_OFF ->
                STATUS_RADIO_OFF

            else ->
                "UNKNOWN_ERROR_$resultCode"
        }

        val success = resultCode == Activity.RESULT_OK

        Log.i(
            TAG,
            "SMS part callback: result=$result, " +
                "jobId=$jobId, requestId=$requestId, " +
                "part=${partIndex + 1}/$totalParts"
        )

        /*
         * This callback reports Android's SMS submission result.
         * It does NOT prove that the recipient received the message.
         *
         * GatewayService aggregates the results of all parts for a job.
         * Do not submit an API result for each individual part here.
         */
        val resultIntent = Intent(
            ACTION_SMS_PART_RESULT
        ).apply {
            setPackage(context.packageName)

            putExtra(EXTRA_RESULT, result)
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            putExtra(EXTRA_JOB_ID, jobId)
            putExtra(EXTRA_GATEWAY_REQUEST_ID, requestId)
            putExtra(EXTRA_PART_INDEX, partIndex)
            putExtra(EXTRA_TOTAL_PARTS, totalParts)
        }

        try {
            context.sendBroadcast(resultIntent)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Unable to forward SMS callback to GatewayService",
                e
            )
        }
    }

    companion object {
        private const val TAG = "SmsSentReceiver"

        // PendingIntent action used by SmsHelper.kt.
        const val ACTION_SMS_RESULT =
            "com.ssmediaos.smsgateway.ACTION_SMS_RESULT"

        // Internal callback action consumed by GatewayService.kt.
        const val ACTION_SMS_PART_RESULT =
            "com.ssmediaos.smsgateway.ACTION_SMS_PART_RESULT"

        const val EXTRA_RESULT = "sms_result"
        const val EXTRA_SUCCESS = "sms_success"
        const val EXTRA_REQUEST_CODE = "request_code"

        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_GATEWAY_REQUEST_ID = "gateway_request_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_TOTAL_PARTS = "total_parts"

        const val STATUS_SMS_SENT = "SMS_SENT"
        const val STATUS_GENERIC_FAILURE = "GENERIC_FAILURE"
        const val STATUS_NO_SERVICE = "NO_SERVICE"
        const val STATUS_NULL_PDU = "NULL_PDU"
        const val STATUS_RADIO_OFF = "RADIO_OFF"
    }
}
