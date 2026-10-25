package com.ssmediaos.smsgateway

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

object SmsHelper {

    private const val TAG = "SmsHelper"

    private const val EXTRA_REQUEST_CODE = "request_code"

    private val requestCodeCounter = AtomicInteger(2000)

    sealed class SendResult {
        data object Success : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    /**
     * Submits an SMS to Android's SMS service.
     *
     * Success means Android accepted the send request.
     * Final delivery is reported separately by SmsSentReceiver.
     */
    fun sendSms(
        context: Context,
        phoneNumber: String,
        message: String
    ): SendResult {

        val appContext = context.applicationContext
        val number = phoneNumber.trim()
        val smsText = message.trim()

        if (number.isBlank()) {
            return SendResult.Failure("Phone number is required.")
        }

        if (smsText.isBlank()) {
            return SendResult.Failure("Message cannot be empty.")
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

            repeat(parts.size) {
                sentIntents.add(
                    createSentPendingIntent(appContext)
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
                "SMS submission requested; parts=${parts.size}"
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

    private fun getSmsManager(
        context: Context
    ): SmsManager? {
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
        context: Context
    ): PendingIntent {

        val requestCode = requestCodeCounter.getAndIncrement()

        val intent = Intent(
            context,
            SmsSentReceiver::class.java
        ).apply {
            action = SmsSentReceiver.ACTION_SMS_RESULT
            putExtra(EXTRA_REQUEST_CODE, requestCode)
            data = android.net.Uri.parse(
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
