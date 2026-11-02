package com.ssmediaos.smsgateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Reads the active SIM subscriptions and persists one explicitly selected
 * subscription for SMS sending. This class never chooses a fallback SIM.
 *
 * The caller must request READ_PHONE_STATE at runtime before calling
 * getAvailableSims(). The selected SIM must also be checked again by
 * SmsHelper immediately before every SMS submission.
 */
object SimSelectionManager {

    private const val TAG = "SimSelectionManager"

    data class SimOption(
        val subscriptionId: Int,
        val slotIndex: Int,
        val displayName: String,
        val carrierName: String,
        val isReady: Boolean,
        val isSelected: Boolean
    ) {
        val title: String
            get() = buildString {
                append("SIM ")
                append(if (slotIndex >= 0) slotIndex + 1 else "?")
                append(" · ")
                append(displayName.ifBlank { carrierName.ifBlank { "Mobile SIM" } })
            }

        val statusText: String
            get() = if (isReady) "Ready" else "Not ready / unavailable"
    }

    sealed class SimResult {
        data class Success(val sim: SimOption) : SimResult()
        data class Failure(val message: String) : SimResult()
    }

    /** Returns the active SIMs visible to this app. */
    fun getAvailableSims(context: Context): List<SimOption> {
        if (!hasPhoneStatePermission(context)) {
            Log.w(TAG, "READ_PHONE_STATE permission is not granted")
            return emptyList()
        }

        val subscriptionManager =
            context.getSystemService(SubscriptionManager::class.java)
                ?: return emptyList()

        val selectedId = SecureConfig.getSelectedSimSubscriptionId(context)

        return try {
            val subscriptions = subscriptionManager.activeSubscriptionInfoList.orEmpty()

            subscriptions
                .mapNotNull { info -> toSimOption(context, info, selectedId) }
                .sortedWith(compareBy<SimOption> { it.slotIndex }.thenBy { it.displayName })
        } catch (security: SecurityException) {
            Log.e(TAG, "Permission denied while reading SIM subscriptions", security)
            emptyList()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read active SIM subscriptions", error)
            emptyList()
        }
    }

    /**
     * Saves only a currently active and READY SIM. It deliberately refuses
     * unknown subscription IDs and never substitutes another SIM.
     */
    fun selectSim(context: Context, subscriptionId: Int): SimResult {
        if (!hasPhoneStatePermission(context)) {
            return SimResult.Failure(
                "Phone permission is required to view and select an active SIM."
            )
        }

        val option = getAvailableSims(context)
            .firstOrNull { it.subscriptionId == subscriptionId }
            ?: return SimResult.Failure(
                "Selected SIM is not currently active. Refresh the SIM list and try again."
            )

        if (!option.isReady) {
            return SimResult.Failure(
                "Selected SIM is not ready. Check that the SIM is enabled and registered on the mobile network."
            )
        }

        if (!SecureConfig.saveSelectedSimSubscriptionId(context, subscriptionId)) {
            return SimResult.Failure("Could not securely save the selected SIM.")
        }

        val saved = getAvailableSims(context)
            .firstOrNull { it.subscriptionId == subscriptionId }
            ?: option.copy(isSelected = true)

        return SimResult.Success(saved.copy(isSelected = true))
    }

    /** Clears the assignment; SMS sending must remain blocked until a SIM is selected again. */
    fun clearSelection(context: Context): Boolean {
        return SecureConfig.clearSelectedSimSubscriptionId(context)
    }

    /** Returns a human-readable state for Settings / Dashboard. */
    fun getSelectedSimStatus(context: Context): String {
        val selectedId = SecureConfig.getSelectedSimSubscriptionId(context)
            ?: return "No SIM selected"

        if (!hasPhoneStatePermission(context)) {
            return "Permission required to verify selected SIM"
        }

        val selected = getAvailableSims(context)
            .firstOrNull { it.subscriptionId == selectedId }
            ?: return "Selected SIM unavailable — SMS blocked"

        return if (selected.isReady) {
            "${selected.title} · Ready"
        } else {
            "${selected.title} · Not ready — SMS blocked"
        }
    }

    /** Checks whether the saved SIM is still active and READY right now. */
    fun isSelectedSimReady(context: Context): Boolean {
        val selectedId = SecureConfig.getSelectedSimSubscriptionId(context)
            ?: return false

        if (!hasPhoneStatePermission(context)) return false

        return getAvailableSims(context).any {
            it.subscriptionId == selectedId && it.isReady
        }
    }

    fun hasPhoneStatePermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun toSimOption(
        context: Context,
        info: SubscriptionInfo,
        selectedId: Int?
    ): SimOption? {
        val ready = try {
            val telephonyManager =
                context.getSystemService(TelephonyManager::class.java)
                    ?: return null
            telephonyManager
                .createForSubscriptionId(info.subscriptionId)
                .simState == TelephonyManager.SIM_STATE_READY
        } catch (security: SecurityException) {
            Log.w(TAG, "Unable to read SIM state for subscription ${info.subscriptionId}", security)
            false
        } catch (error: Exception) {
            Log.w(TAG, "SIM state check failed for subscription ${info.subscriptionId}", error)
            false
        }

        return SimOption(
            subscriptionId = info.subscriptionId,
            slotIndex = info.simSlotIndex,
            displayName = info.displayName?.toString().orEmpty(),
            carrierName = info.carrierName?.toString().orEmpty(),
            isReady = ready,
            isSelected = info.subscriptionId == selectedId
        )
    }
}

