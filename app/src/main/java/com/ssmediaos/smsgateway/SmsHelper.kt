
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

    private val requestCodeCounter = AtomicInteger(2000)

    sealed class SendResult {
        data object Success : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    fun sendSms(
        context: Context,
        phoneNumber: String,
        message: String
    ): SendResult {
        val number = phoneNumber.trim()

        if (number.isBlank()) {
            return SendResult.Failure("Phone number is required.")
        }

        if (message.isBlank()) {
            return SendResult.Failure("Message cannot be empty.")
        }

        if (
            context.checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return SendResult.Failure(
                "SMS permission has not been granted."
            )
        }

        return try {
            val smsManager: SmsManager =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }

            val parts = smsManager.divideMessage(message)

            if (parts.isEmpty()) {
                return SendResult.Failure(
                    "Message could not be divided into SMS parts."
                )
            }

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    number,
                    null,
                    message,
                    createSentPendingIntent(context),
                    null
                )
            } else {
                val sentIntents = ArrayList<PendingIntent>(parts.size)

                repeat(parts.size) {
                    sentIntents.add(createSentPendingIntent(context))
                }

                smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    parts,
                    sentIntents,
                    null
                )
            }

            Log.i(TAG, "SMS submission requested")
            SendResult.Success

        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission/security error", e)
            SendResult.Failure(
                e.message ?: "SMS permission or security error."
            )
        } catch (e: Exception) {
            Log.e(TAG, "SMS submission failed", e)
            SendResult.Failure(
                e.message ?: "Unable to submit SMS."
            )
        }
    }

    private fun createSentPendingIntent(
        context: Context
    ): PendingIntent {
        val intent = Intent(context, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SMS_RESULT
            putExtra(
                "request_code",
                requestCodeCounter.getAndIncrement()
            )
        }

        var flags = PendingIntent.FLAG_UPDATE_CURRENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }

        return PendingIntent.getBroadcast(
            context,
            requestCodeCounter.getAndIncrement(),
            intent,
            flags
        )
    }
}

