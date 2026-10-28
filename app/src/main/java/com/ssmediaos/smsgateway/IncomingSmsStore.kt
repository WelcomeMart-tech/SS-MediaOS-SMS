package com.ssmediaos.smsgateway

import android.content.Context
import org.json.JSONObject

/**
 * Incoming SMS storage is intentionally disabled.
 *
 * This keeps the existing method signatures so other original files that still
 * reference IncomingSmsStore continue to compile, while this app does not save
 * or expose incoming SMS messages.
 */
object IncomingSmsStore {

    private const val PREFS_NAME = "incoming_sms_store"
    private const val KEY_MESSAGES = "messages"

    /** No-op: incoming SMS messages are not stored by this gateway. */
    @Synchronized
    fun save(
        context: Context,
        sender: String,
        message: String,
        timestamp: Long
    ) {
        // Intentionally disabled. This app is for sending SMS only.
    }

    /** Always returns an empty list because incoming SMS storage is disabled. */
    @Synchronized
    fun getAll(context: Context): List<JSONObject> = emptyList()

    /** Removes any messages saved by an older version of the app. */
    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MESSAGES)
            .apply()
    }
}
