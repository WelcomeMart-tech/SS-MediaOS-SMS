package com.ssmediaos.smsgateway

import android.content.Context
import java.net.URI
import java.util.UUID

object SecureConfig {

    private const val PREFS_NAME = "ssmediaos_gateway_config"

    // Production server: fixed, not user-editable.
    const val DEFAULT_SERVER_URL = "https://otp.ssmediaos.in"

    // Kept for compatibility with existing files.
    // Do not use this as an actual device ID.
    const val DEFAULT_DEVICE_CODE = ""

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_DEVICE_TOKEN = "device_token"

    private const val LEGACY_DEVICE_CODE = "Device-01"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /**
     * The server is fixed for this application.
     * Older saved server URLs are not used.
     */
    fun getServerUrl(context: Context): String =
        DEFAULT_SERVER_URL

    /**
     * Returns a stable, unique ID for this installation.
     *
     * The ID is generated once and stored locally.
     * Different installations get different IDs.
     */
    @Synchronized
    fun getDeviceCode(context: Context): String {
        val prefs = preferences(context)
        val savedId = prefs.getString(KEY_DEVICE_ID, null)?.trim()

        if (
            !savedId.isNullOrBlank() &&
            savedId != LEGACY_DEVICE_CODE
        ) {
            return savedId
        }

        val newId = "SSM-${UUID.randomUUID()}"

        prefs.edit()
            .putString(KEY_DEVICE_ID, newId)
            .remove(KEY_DEVICE_TOKEN)
            .commit()

        return newId
    }

    // Compatibility aliases used by existing project files.
    fun getDeviceId(context: Context): String =
        getDeviceCode(context)

    fun getActivationToken(context: Context): String? =
        preferences(context)
            .getString(KEY_API_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun getApiToken(context: Context): String? =
        getActivationToken(context)

    fun getCompanyApiKey(context: Context): String? =
        getActivationToken(context)

    /**
     * This is the server-issued device token.
     * Never display it in the settings UI.
     */
    fun getDeviceToken(context: Context): String? =
        preferences(context)
            .getString(KEY_DEVICE_TOKEN, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Saves the fixed server, installation ID and company API key.
     * The server URL supplied by older callers is intentionally ignored.
     */
    @Synchronized
    fun saveSettings(
        context: Context,
        serverUrl: String,
        apiToken: String,
        deviceId: String
    ): Boolean {
        val companyApiKey = apiToken.trim()

        if (companyApiKey.isBlank()) {
            return false
        }

        val actualDeviceId = getDeviceCode(context)

        val suppliedDeviceId = deviceId.trim()

        // Prevent older screens from replacing the generated ID
        // with the legacy fixed "Device-01" value.
        if (
            suppliedDeviceId.isNotBlank() &&
            suppliedDeviceId != actualDeviceId &&
            suppliedDeviceId != LEGACY_DEVICE_CODE
        ) {
            return false
        }

        val prefs = preferences(context)
        val oldApiKey = prefs.getString(KEY_API_TOKEN, null)

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
            .putString(KEY_DEVICE_ID, actualDeviceId)
            .putString(KEY_API_TOKEN, companyApiKey)

        // A different company key requires fresh device activation.
        if (oldApiKey != companyApiKey) {
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
            serverUrl = DEFAULT_SERVER_URL,
            apiToken = activationToken,
            deviceId = deviceCode
        )
    }

    /**
     * Stores the device token returned by the activation endpoint.
     */
    fun saveDeviceToken(
        context: Context,
        deviceToken: String
    ): Boolean {
        val value = deviceToken.trim()

        if (value.isBlank()) {
            return false
        }

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

    /**
     * Compatibility method.
     * Server URL remains fixed regardless of the supplied value.
     */
    fun saveServerUrl(
        context: Context,
        serverUrl: String
    ): Boolean {
        val prefs = preferences(context)
        val oldUrl = prefs.getString(KEY_SERVER_URL, null)

        val editor = prefs.edit()
            .putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)

        if (
            oldUrl != null &&
            oldUrl.trimEnd('/') != DEFAULT_SERVER_URL
        ) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
    }

    /**
     * Compatibility method for existing project code.
     * The generated installation ID cannot be replaced by a fixed ID.
     */
    fun saveDeviceId(
        context: Context,
        deviceId: String
    ): Boolean {
        val actualId = getDeviceCode(context)
        val suppliedId = deviceId.trim()

        return suppliedId == actualId ||
            suppliedId == LEGACY_DEVICE_CODE
    }

    fun saveDeviceCode(
        context: Context,
        deviceCode: String
    ): Boolean {
        return saveDeviceId(context, deviceCode)
    }

    fun saveApiToken(
        context: Context,
        apiToken: String
    ): Boolean {
        val value = apiToken.trim()

        if (value.isBlank()) {
            return false
        }

        val prefs = preferences(context)
        val oldApiKey = prefs.getString(KEY_API_TOKEN, null)

        val editor = prefs.edit()
            .putString(KEY_API_TOKEN, value)

        if (oldApiKey != value) {
            editor.remove(KEY_DEVICE_TOKEN)
        }

        return editor.commit()
    }

    fun saveActivationToken(
        context: Context,
        activationToken: String
    ): Boolean {
        return saveApiToken(context, activationToken)
    }

    fun clearSettings(context: Context): Boolean {
        return preferences(context)
            .edit()
            .clear()
            .commit()
    }

    /**
     * Kept for compatibility with older code.
     * Only HTTPS URLs with a valid host are accepted.
     */
    private fun normalizeServerUrl(value: String): String? {
        val normalized = value.trim().trimEnd('/')

        if (normalized.isBlank()) {
            return null
        }

        return try {
            val uri = URI(normalized)

            val validScheme =
                uri.scheme.equals("https", ignoreCase = true)

            val validHost =
                !uri.host.isNullOrBlank()

            val noUserInfo =
                uri.rawUserInfo == null

            val noQueryOrFragment =
                uri.rawQuery == null &&
                    uri.rawFragment == null

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
