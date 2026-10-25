package com.ssmediaos.smsgateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.os.Build
import android.util.Log

object SmsHelper {

    private const val TAG = "SmsHelper"

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
            return SendResult.Failure("SMS permission has not been granted.")
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)

            if (parts.size == 1) {
                smsManager.sendTextMessage(
                    number,
                    null,
                    message,
                    null,
                    null
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    number,
                    null,
                    parts,
                    null,
                    null
                )
            }

            Log.i(TAG, "SMS submission requested")
            SendResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "SMS submission failed", e)
            SendResult.Failure(e.message ?: "Unable to submit SMS.")
        }
    }
}
