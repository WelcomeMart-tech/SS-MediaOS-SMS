package com.ssmediaos.smsgateway

import android.content.Context
import android.content.SharedPreferences

object SecureConfig {

    private const val PREFS_NAME = "ss_mediaos_gateway_config"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"

    private fun preferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    fun saveServerUrl(context: Context, url: String): Boolean {
        val normalizedUrl = url.trim().trimEnd('/')

        if (!normalizedUrl.startsWith("https://")) {
            return false
        }

        preferences(context).edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .apply()

        return true
    }

    fun getServerUrl(context: Context): String? {
        return preferences(context).getString(KEY_SERVER_URL, null)
    }

    fun saveDeviceId(context: Context, deviceId: String) {
        preferences(context).edit()
            .putString(KEY_DEVICE_ID, deviceId.trim())
            .apply()
    }

    fun getDeviceId(context: Context): String? {
        return preferences(context).getString(KEY_DEVICE_ID, null)
    }

    fun clearConfiguration(context: Context) {
        preferences(context).edit().clear().apply()
    }
}
