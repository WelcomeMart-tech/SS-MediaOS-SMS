
package com.ssmediaos.smsgateway

import android.content.Context

object SecureConfig {

    private const val PREFS_NAME = "ssmediaos_gateway_config"

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_TOKEN = "api_token"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun getServerUrl(context: Context): String? {
        return preferences(context)
            .getString(KEY_SERVER_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun getDeviceId(context: Context): String? {
        return preferences(context)
            .getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun getApiToken(context: Context): String? {
        return preferences(context)
            .getString(KEY_API_TOKEN, null)
            ?.takeIf { it.isNotEmpty() }
    }

    fun saveSettings(
        context: Context,
        serverUrl: String,
        apiToken: String,
        deviceId: String
    ): Boolean {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val normalizedToken = apiToken.trim()
        val normalizedDeviceId = deviceId.trim()

        if (!isValidServerUrl(normalizedUrl)) {
            return false
        }

        if (normalizedToken.isBlank() || normalizedDeviceId.isBlank()) {
            return false
        }

        return preferences(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, normalizedToken)
            .putString(KEY_DEVICE_ID, normalizedDeviceId)
            .commit()
    }

    fun saveServerUrl(context: Context, serverUrl: String): Boolean {
        val normalizedUrl = serverUrl.trim().trimEnd('/')

        if (!isValidServerUrl(normalizedUrl)) {
            return false
        }

        return preferences(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .commit()
    }

    fun saveDeviceId(context: Context, deviceId: String): Boolean {
        val value = deviceId.trim()

        if (value.isBlank()) {
            return false
        }

        return preferences(context).edit()
            .putString(KEY_DEVICE_ID, value)
            .commit()
    }

    fun saveApiToken(context: Context, apiToken: String): Boolean {
        val value = apiToken.trim()

        if (value.isBlank()) {
            return false
        }

        return preferences(context).edit()
            .putString(KEY_API_TOKEN, value)
            .commit()
    }

    fun clearSettings(context: Context): Boolean {
        return preferences(context)
            .edit()
            .clear()
            .commit()
    }

    private fun isValidServerUrl(value: String): Boolean {
        if (value.isBlank()) return false

        return try {
            val uri = java.net.URI(value)

            (uri.scheme == "https" || uri.scheme == "http") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        } catch (_: Exception) {
            false
        }
    }
}

