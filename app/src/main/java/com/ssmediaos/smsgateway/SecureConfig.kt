package com.ssmediaos.smsgateway

import android.content.Context
import java.net.URI

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

    fun getServerUrl(context: Context): String? =
        preferences(context)
            .getString(KEY_SERVER_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // Compatibility methods for existing files.
    // These values represent Device Code and Activation Token
    // until the API activation flow is integrated.
    fun getDeviceId(context: Context): String? =
        getDeviceCode(context)

    fun getApiToken(context: Context): String? =
        getActivationToken(context)

    fun getDeviceCode(context: Context): String? =
        preferences(context)
            .getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun getActivationToken(context: Context): String? =
        preferences(context)
            .getString(KEY_API_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun saveSettings(
        context: Context,
        serverUrl: String,
        apiToken: String,
        deviceId: String
    ): Boolean {
        val normalizedUrl = normalizeServerUrl(serverUrl)
            ?: return false

        val activationToken = apiToken.trim()
        val deviceCode = deviceId.trim()

        if (activationToken.isBlank() || deviceCode.isBlank()) {
            return false
        }

        return preferences(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, activationToken)
            .putString(KEY_DEVICE_ID, deviceCode)
            .commit()
    }

    fun saveActivationSettings(
        context: Context,
        serverUrl: String,
        deviceCode: String,
        activationToken: String
    ): Boolean {
        return saveSettings(
            context = context,
            serverUrl = serverUrl,
            apiToken = activationToken,
            deviceId = deviceCode
        )
    }

    fun saveServerUrl(
        context: Context,
        serverUrl: String
    ): Boolean {
        val normalizedUrl = normalizeServerUrl(serverUrl)
            ?: return false

        return preferences(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .commit()
    }

    fun saveDeviceId(
        context: Context,
        deviceId: String
    ): Boolean {
        val value = deviceId.trim()

        if (value.isBlank()) return false

        return preferences(context).edit()
            .putString(KEY_DEVICE_ID, value)
            .commit()
    }

    fun saveDeviceCode(
        context: Context,
        deviceCode: String
    ): Boolean = saveDeviceId(context, deviceCode)

    fun saveApiToken(
        context: Context,
        apiToken: String
    ): Boolean {
        val value = apiToken.trim()

        if (value.isBlank()) return false

        return preferences(context).edit()
            .putString(KEY_API_TOKEN, value)
            .commit()
    }

    fun saveActivationToken(
        context: Context,
        activationToken: String
    ): Boolean = saveApiToken(context, activationToken)

    fun clearSettings(context: Context): Boolean {
        return preferences(context)
            .edit()
            .clear()
            .commit()
    }

    private fun normalizeServerUrl(value: String): String? {
        val normalized = value.trim().trimEnd('/')

        if (normalized.isBlank()) return null

        return try {
            val uri = URI(normalized)

            val validScheme =
                uri.scheme.equals("https", ignoreCase = true)

            val validHost =
                !uri.host.isNullOrBlank()

            val noUserInfo = uri.userInfo == null
            val noQueryOrFragment =
                uri.rawQuery == null && uri.rawFragment == null

            if (
                validScheme &&
                validHost &&
                noUserInfo &&
                noQueryOrFragment
            ) {
                normalized
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
