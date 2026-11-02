package com.ssmediaos.smsgateway

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends SMS only through the SIM explicitly selected in SecureConfig.
 *
 * A successful return means Android accepted the send request. The actual
 * per-part send result is delivered asynchronously to SmsSentReceiver and
 * then consumed by GatewayService. It is not a delivery receipt.
 */
object SmsHelper {

    private const val TAG = "SmsHelper"

    // Seed with process-specific time so a process restart is unlikely to
    // reuse a PendingIntent request code that is still awaiting a callback.
    private val requestCodeCounter = AtomicInteger(
        (System.nanoTime() and 0x7fffffffL).toInt()
    )

    sealed class SendResult {
        data object Success : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    /**
     * Submit a single-part or multipart SMS using the selected SIM.
     *
     * jobId and requestId are copied into every sent callback so the service
     * can safely associate each callback with the claimed server job.
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

        if (appContext.checkSelfPermission(Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return SendResult.Failure("SMS permission has not been granted.")
        }

        // SubscriptionManager requires READ_PHONE_STATE on supported Android
        // versions. Fail clearly instead of allowing a silent default-SIM use.
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return SendResult.Failure(
                "Phone-state permission is required to verify the assigned SIM. Grant it in app permissions."
            )
        }

        return try {
            val selectedSubscriptionId =
                SecureConfig.getSelectedSimSubscriptionId(appContext)
                    ?: return SendResult.Failure(
                        "No SIM is selected. Open Gateway Settings and select the assigned SIM."
                    )

            val subscriptionManager = appContext.getSystemService(
                SubscriptionManager::class.java
            ) ?: return SendResult.Failure(
                "SIM subscription service is not available on this device."
            )

            val activeSubscriptions = try {
                subscriptionManager.activeSubscriptionInfoList.orEmpty()
            } catch (e: SecurityException) {
                Log.e(TAG, "Unable to inspect active SIM subscriptions", e)
                return SendResult.Failure(
                    "Cannot verify the assigned SIM. Grant phone-state permission and select the SIM again."
                )
            }

            if (activeSubscriptions.none { it.subscriptionId == selectedSubscriptionId }) {
                return SendResult.Failure(
                    "The assigned SIM is unavailable. SMS was not sent; select the assigned SIM again."
                )
            }

            val smsManager = getSmsManager(appContext, selectedSubscriptionId)
                ?: return SendResult.Failure(
                    "SMS service for the assigned SIM is not available."
                )

            val parts = try {
                smsManager.divideMessage(smsText)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to split SMS message", e)
                return SendResult.Failure(
                    e.message ?: "Message could not be prepared for SMS sending."
                )
            }

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
                "SMS submission requested: jobId=$jobId, parts=${parts.size}, " +
                    "subscriptionId=$selectedSubscriptionId"
            )
            SendResult.Success

        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission/security error", e)
            SendResult.Failure(e.message ?: "SMS permission or security error.")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid SMS arguments", e)
            SendResult.Failure(
                e.message ?: "Invalid phone number or SMS arguments."
            )
        } catch (e: Exception) {
            Log.e(TAG, "SMS submission failed", e)
            SendResult.Failure(e.message ?: "Unable to submit SMS.")
        }
    }

    private fun getSmsManager(
        context: Context,
        subscriptionId: Int
    ): SmsManager? {
        return try {
            val baseManager = context.getSystemService(SmsManager::class.java)
                ?: return null
            baseManager.createForSubscriptionId(subscriptionId)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to obtain SmsManager for assigned SIM", e)
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
        val uniqueCallbackId = "${System.currentTimeMillis()}-$requestCode"

        val intent = Intent(context, SmsSentReceiver::class.java).apply {
            action = SmsSentReceiver.ACTION_SMS_RESULT
            putExtra(SmsSentReceiver.EXTRA_REQUEST_CODE, requestCode)
            putExtra(SmsSentReceiver.EXTRA_JOB_ID, jobId)
            putExtra(SmsSentReceiver.EXTRA_GATEWAY_REQUEST_ID, requestId)
            putExtra(SmsSentReceiver.EXTRA_PART_INDEX, partIndex)
            putExtra(SmsSentReceiver.EXTRA_TOTAL_PARTS, totalParts)
            data = Uri.parse("ssmediaos://sms-sent/$uniqueCallbackId")
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
