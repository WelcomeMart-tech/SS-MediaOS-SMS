
package com.ssmediaos.smsgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Restarts the SS MediaOS SMS Gateway after a normal device reboot or app update,
 * but only when the user previously enabled the Gateway.
 *
 * This receiver never activates or re-links a device by itself. It only resumes
 * the already configured GatewayService; the Main Server remains authoritative
 * for device, company and institution authorization.
 */
class GatewayBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

        // Do not start the Gateway unless the user explicitly enabled it earlier.
        val gatewayEnabled = prefs.getBoolean(PREF_GATEWAY_ENABLED, false)
        if (!gatewayEnabled) {
            Log.i(TAG, "Gateway remains stopped: user has not enabled it")
            return
        }

        // A stored token/configuration is required before attempting a resume.
        // The service performs the actual authenticated Main Server connection.
        val serviceIntent = Intent(context, GatewayService::class.java).apply {
            action = GatewayService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Previously enabled Gateway resume requested")
        } catch (exception: Exception) {
            // Do not clear the user's enabled preference. A later app launch or
            // permitted system restart can retry; log only the exception class.
            Log.e(
                TAG,
                "Could not request Gateway resume: ${exception.javaClass.simpleName}"
            )
        }
    }

    companion object {
        private const val TAG = "GatewayBootReceiver"
        private const val PREFS_NAME = "ss_mediaos_gateway_status"
        private const val PREF_GATEWAY_ENABLED = "gateway_enabled_by_user"
    }
}
