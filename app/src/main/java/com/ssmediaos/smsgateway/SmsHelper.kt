package com.ssmediaos.smsgateway

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object SmsHelper {

    private const val TAG = "SmsHelper"

    private val requestCodeCounter = AtomicInteger(2000)

    sealed class SendResult {
        data object Success : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    /**
     * Success means Android accepted the send request.
     * It does not mean that the recipient received the SMS.
     *
     * jobId and requestId connect Android callbacks to the server job.
     */
    fun sendSms(
        context: Context,
        phoneNumber: String,
        message: String,
        jobId: Long,
        requestId: String
    ): SendResult {

        val appContext = context.applicationContext
        val number = phoneNumber.trim()
        val smsText = message.trim()
        val normalizedRequestId = requestId.trim()

        if (number.isBlank()) {
            return SendResult.Failure("Phone number is required.")
        }

        if (smsText.isBlank()) {
            return SendResult.Failure("Message cannot be empty.")
        }

        if (jobId < 1L) {
            return SendResult.Failure("Invalid SMS job ID.")
        }

        if (!normalizedRequestId.matches(Regex("^[a-f0-9]{32}$"))) {
            return SendResult.Failure("Invalid gateway request ID.")
        }

        if (
            appContext.checkSelfPermission(
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return SendResult.Failure(
                "SMS permission has not been granted."
            )
        }

        return try {
            val smsManager = getSmsManager(appContext)
                ?: return SendResult.Failure(
                    "SMS service is not available on this device."
                )

            val parts = smsManager.divideMessage(smsText)

            if (parts.isEmpty()) {
                return SendResult.Failure(
                    "Message could not be divided into SMS parts."
                )
            }

            val sentIntents = ArrayList<PendingIntent>(parts.size)

            parts.forEachIndexed { index, _ ->
                sentIntents.add(
                    createSentPendingIntent(
                        context = appContext,
                        jobId = jobId,
                        requestId = normalizedRequestId,
                        partIndex = index,
                        totalParts = parts.size
                    )
                )
            }

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    number,
                    null,
                    parts[0],
                    sentIntents[0],
                    null
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    ArrayList(parts),
                    sentIntents,
                    null
                )
            }

            Log.i(
                TAG,
                "SMS submission requested; jobId=$jobId, " +
                    "parts=${parts.size}"
            )

            SendResult.Success

        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission/security error", e)
            SendResult.Failure(
                e.message ?: "SMS permission or security error."
            )
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid SMS arguments", e)
            SendResult.Failure(
                e.message ?: "Invalid phone number or SMS arguments."
            )
        } catch (e: Exception) {
            Log.e(TAG, "SMS submission failed", e)
            SendResult.Failure(
                e.message ?: "Unable to submit SMS."
            )
        }
    }

    private fun getSmsManager(context: Context): SmsManager? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain SmsManager", e)
            null
        }
    }

    private fun createSentPendingIntent(
        context: Context,
        jobId: Long,
        requestId: String,
        partIndex: Int,
        totalParts: Int
    ): PendingIntent {

        val requestCode = requestCodeCounter.getAndIncrement()

        val intent = Intent(
            context,
            SmsSentReceiver::class.java
        ).apply {
            action = SmsSentReceiver.ACTION_SMS_RESULT

            putExtra(
                SmsSentReceiver.EXTRA_REQUEST_CODE,
                requestCode
            )
            putExtra(
                SmsSentReceiver.EXTRA_JOB_ID,
                jobId
            )
            putExtra(
                SmsSentReceiver.EXTRA_GATEWAY_REQUEST_ID,
                requestId
            )
            putExtra(
                SmsSentReceiver.EXTRA_PART_INDEX,
                partIndex
            )
            putExtra(
                SmsSentReceiver.EXTRA_TOTAL_PARTS,
                totalParts
            )

            data = Uri.parse(
                "ssmediaos://sms-sent/$requestCode"
            )
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            flags
        )
    }
}
