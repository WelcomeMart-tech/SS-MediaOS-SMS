package com.ssmediaos.smsgateway

import android.content.Context
import java.net.URI
import java.util.UUID

object SecureConfig {

    private const val PREFS_NAME = "ssmediaos_gateway_config"

    const val DEFAULT_SERVER_URL = "https://otp.ssmediaos.in"

    // Kept for compatibility with older files.
    // Never use this fixed value as the actual device ID.
    const val DEFAULT_DEVICE_CODE = ""

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun getServerUrl(context: Context): String =
        preferences(context)
            .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SERVER_URL

    /**
     * Creates one unique ID per app installation.
     * The ID remains stable across normal app restarts.
     */
    fun getDeviceCode(context: Context): String {
        val prefs = preferences(context)

        val existing = prefs.getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (existing != null) {
            return existing
        }

        val generated = "SSM-${UUID.randomUUID()}"

        return if (
            prefs.edit()
                .putString(KEY_DEVICE_ID, generated)
                .commit()
        ) {
            generated
        } else {
            throw IllegalStateException(
                "Could not save the device ID."
            )
        }
    }

    // Compatibility alias for existing app files.
    fun getDeviceId(context: Context): String =
        getDeviceCode(context)

    fun getApiToken(context: Context): String? =
        getCompanyApiKey(context)

    fun getActivationToken(context: Context): String? =
        getCompanyApiKey(context)

    fun getCompanyApiKey(context: Context): String? =
        preferences(context)
            .getString(KEY_API_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun getDeviceToken(context: Context): String? =
        preferences(context)
            .getString(KEY_DEVICE_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Saves server URL, company API key, and device ID.
     * The production server URL is validated as HTTPS.
     */
    fun saveSettings(
        context: Context,
        serverUrl: String,
        apiToken: String,
        deviceId: String
    ): Boolean {
        val normalizedUrl = normalizeServerUrl(serverUrl)
            ?: return false

        val companyApiKey = apiToken.trim()
        val deviceCode = deviceId.trim()

        if (
            companyApiKey.isBlank() ||
            deviceCode.isBlank()
        ) {
            return false
        }

        val prefs = preferences(context)

        val existingUrl = prefs.getString(KEY_SERVER_URL, null)
        val existingCode = prefs.getString(KEY_DEVICE_ID, null)
        val existingApiKey = prefs.getString(KEY_API_TOKEN, null)

        val activationDetailsChanged =
            existingUrl != normalizedUrl ||
            existingCode != deviceCode ||
            existingApiKey != companyApiKey

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, companyApiKey)
            .putString(KEY_DEVICE_ID, deviceCode)

        if (activationDetailsChanged) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
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

    fun saveDeviceToken(
        context: Context,
        deviceToken: String
    ): Boolean {
        val value = deviceToken.trim()
        if (value.isBlank()) return false

        return preferences(context)
            .edit()
            .putString(KEY_DEVICE_TOKEN, value)
            .commit()
    }

    fun clearDeviceToken(context: Context): Boolean {
        return preferences(context)
            .edit()
            .remove(KEY_DEVICE_TOKEN)
            .commit()
    }

    fun saveServerUrl(
        context: Context,
        serverUrl: String
    ): Boolean {
        val normalizedUrl = normalizeServerUrl(serverUrl)
            ?: return false

        val prefs = preferences(context)
        val oldUrl = prefs.getString(KEY_SERVER_URL, null)

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)

        if (oldUrl != normalizedUrl) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
    }

    fun saveDeviceId(
        context: Context,
        deviceId: String
    ): Boolean {
        val value = deviceId.trim()
        if (value.isBlank()) return false

        val prefs = preferences(context)
        val oldCode = prefs.getString(KEY_DEVICE_ID, null)

        val editor = prefs.edit()
            .putString(KEY_DEVICE_ID, value)

        if (oldCode != value) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
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

        val prefs = preferences(context)
        val oldToken = prefs.getString(KEY_API_TOKEN, null)

        val editor = prefs.edit()
            .putString(KEY_API_TOKEN, value)

        if (oldToken != value) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
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

            val validHost = !uri.host.isNullOrBlank()
            val noUserInfo = uri.rawUserInfo == null
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
